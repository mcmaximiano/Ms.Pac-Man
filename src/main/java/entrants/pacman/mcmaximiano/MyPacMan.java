package entrants.pacman.mcmaximiano;

import pacman.controllers.PacmanController;
import pacman.game.Constants;
import pacman.game.Constants.DM;
import pacman.game.Constants.MOVE;
import pacman.game.Game;
import pacman.game.info.GameInfo;
import pacman.game.internal.Ghost;
import pacman.game.internal.PacMan;
import prediction.GhostLocation;
import prediction.PillModel;
import prediction.fast.GhostPredictionsFast;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Random;



import pacman.game.internal.Maze;


@SuppressWarnings("Duplicates")
/*
 * This is the class you need to modify for your entry. In particular, you need to
 * fill in the getMove() method. Any additional classes you write should either
 * be placed in this package or sub-packages (e.g., entrants.pacman.username).
 */
public class MyPacMan extends PacmanController {

    private MOVE myMove = MOVE.NEUTRAL;

    private Maze currentMaze;
    private GhostPredictionsFast predictions;
    private PillModel pillModel;
    private int[] ghostEdibleTime;
    protected Game mostRecentGame;
    protected int maxTreeDepth = 30; //Should play around with different values and compare results
    protected int maxPlayoutDepth = 250; //Should play around with different values and compare results

    public MyPacMan() {
        ghostEdibleTime = new int[Constants.GHOST.values().length];
    }

    public MOVE getMove(Game game, long timeToDecide) {

        //We need a model of the game! Do this to set initial state of the maze:
        if (currentMaze != game.getCurrentMaze() /*|| mostRecentGame != game*/){
            currentMaze = game.getCurrentMaze();
            predictions = null;
            pillModel = null;
            System.out.println("New Maze");
            int level = game.getCurrentLevel() + 1;
            System.out.println("Level: " + level);
            Arrays.fill(ghostEdibleTime, -1);
        }

        mostRecentGame = game;

        if (game.gameOver()) return null;

        if (game.wasPacManEaten()) { //This means PM was eaten in the last time step and just respawned
            predictions = null;
        }

        if (predictions == null) {
            predictions = new GhostPredictionsFast(game.getCurrentMaze()); //Predicts ghosts' movement
            predictions.preallocate();
        }
        if (pillModel == null) {
            pillModel = new PillModel(game.getNumberOfPills());

            int[] indices = game.getCurrentMaze().pillIndices; //Indices are pill spawn points
            for (int index : indices) {
                pillModel.observe(index, true); //Put a pill in each pill spawn point
            }
        }

        // Update the pill model with what isn't available anymore
        // (Because when PM goes through a pill, the pill disappears)
        int pillIndex = game.getPillIndex(game.getPacmanCurrentNodeIndex());
        if (pillIndex != -1) {
            Boolean pillState = game.isPillStillAvailable(pillIndex);
            if (pillState != null && !pillState) { //If there is supposed to be a pill there, but it's not available
                pillModel.observe(pillIndex, false); //Then let's tell the game that there is no pill!
            }
        }

        // Get observations of ghosts and pass them in to the predictor (accounts for partial observability)
        for (Constants.GHOST ghost : Constants.GHOST.values()) {
            if (ghostEdibleTime[ghost.ordinal()] != -1) {
                ghostEdibleTime[ghost.ordinal()]--;
            }

            int ghostIndex = game.getGhostCurrentNodeIndex(ghost);
            if (ghostIndex != -1) { //We see the ghost!
                predictions.observe(ghost, ghostIndex, game.getGhostLastMoveMade(ghost));
                ghostEdibleTime[ghost.ordinal()] = game.getGhostEdibleTime(ghost);
            } else { //We do not see the ghost...
                List<GhostLocation> locations = predictions.getGhostLocations(ghost);
                locations.stream().filter(location -> game.isNodeObservable(location.getIndex())).forEach(location -> {
                    predictions.observeNotPresent(ghost, location.getIndex());
                });
            }
        }
        //Now we have the game modeled! Next comes MCTS:
        Node root = new Node(this, game);
        while(System.currentTimeMillis() < timeToDecide) {
            Game copy = obtainDeterminisedState(game); //MCTS can't deal with PO by itself. We give it a copy of the game without PO, so MCTS thinks it sees everything
            //Select & Expand
            Node node = root.select_expand(copy); //This method fully expands the current node and then selects the best child
            // Play-out
            double gameScore = node.playout(copy);
            // Back-propagate
            node.backPropagate(gameScore);
        }
        predictions.update();

        //System.out.println(root.selectBestMove(game));
        return root.selectBestMove(game);
    }

    private Game obtainDeterminisedState(Game game) {
        GameInfo info = game.getPopulatedGameInfo();
        info.setPacman(new PacMan(game.getPacmanCurrentNodeIndex(), game.getPacmanLastMoveMade(), 0, false));
        EnumMap<Constants.GHOST, GhostLocation> locations = predictions.sampleLocations();
        info.fixGhosts(ghost -> {
            GhostLocation location = locations.get(ghost);
            if (location != null) {
                int edibleTime = ghostEdibleTime[ghost.ordinal()];
                return new Ghost(ghost, location.getIndex(), edibleTime, 0, location.getLastMoveMade());
            } else {
                return new Ghost(ghost, game.getGhostInitialNodeIndex(), 0, 0, MOVE.NEUTRAL);
            }
        });

        for (int i = 0; i < pillModel.getPills().length(); i++) {
            info.setPillAtIndex(i, pillModel.getPills().get(i));
        }
        return game.getGameFromInfo(info);
    }
}

class Node {

    private final MyPacMan MyPacMan;
    private Node parent;
    private MOVE prevMove;
    private MOVE[] legalMoves;
    private Node[] children;
    private int expandedChildren;

    private int visits;
    private double score;
    private int treeDepth;

    /* Constructors */
    public Node(MyPacMan MyPacMan, Game game) { //Constructor for root node
        this.MyPacMan = MyPacMan;
        treeDepth = 0;

        this.legalMoves = getLegalMovesNotIncludingBackwards(game);
        //this.legalMoves = getAllLegalMoves(game);

        this.children = new Node[legalMoves.length];
    }

    public Node(Node parent, MOVE previousMove, MOVE[] legalMoves) { //Constructor for child node
        this.MyPacMan = parent.MyPacMan;
        this.parent = parent;
        this.treeDepth = parent.treeDepth + 1;
        this.prevMove = previousMove;
        this.legalMoves = legalMoves;
        this.children = new Node[legalMoves.length];
    }

    /* MCTS methods */
    public Node select_expand(Game game) {
        Node current = this;
        while (current.treeDepth < MyPacMan.maxTreeDepth && !game.gameOver()) {
            if (current.isFullyExpanded()) {
                current = current.selectBestChild();
                game.advanceGame(current.prevMove, getBasicGhostMoves(game)); //Assume ghosts' behaviour is simple
                //game.advanceGame(current.prevMove, getBasicGhostMoves(game)); //Assume ghosts' behaviour is random
            } else { //Should expand all children before choosing the best one
                current = current.expand(game);
                game.advanceGame(current.prevMove, getBasicGhostMoves(game)); //Assume ghosts' behaviour is simple
                return current;
            }
        }
        return current;
    }

    public double playout(Game game) {
        int depth = treeDepth;
        Random random = new Random();
        while (depth < MyPacMan.maxPlayoutDepth) {
            if (game.gameOver()) break;
            //MOVE[] legalMoves = getLegalMovesNotIncludingBackwards(game);
            MOVE[] legalMoves = getAllLegalMoves(game);
            MOVE randomMove = legalMoves[random.nextInt(legalMoves.length)];
            game.advanceGame(randomMove, getBasicGhostMoves(game));
            depth++;
        }
        return calculateGameScore(game);
    }

    public void backPropagate(double value) {
        Node current = this;
        while (current.parent != null) {
            current.visits++;
            current.score += value;
            current = current.parent;
        }
        // Root node
        current.visits++;
    }


    /* Auxiliar methods */

    protected MOVE[] getLegalMovesNotIncludingBackwards(Game game) {
        return game.getCurrentMaze().graph[game.getPacmanCurrentNodeIndex()].allPossibleMoves.get(game.getPacmanLastMoveMade());
    }

    protected MOVE[] getAllLegalMoves(Game game) {
        Maze maze = game.getCurrentMaze();
        int index = game.getPacmanCurrentNodeIndex();
        return maze.graph[index].neighbourhood.keySet().toArray(new MOVE[maze.graph[index].neighbourhood.keySet().size()]);
    }

    protected EnumMap<Constants.GHOST, MOVE> getBasicGhostMoves(Game game) {
        EnumMap<Constants.GHOST, MOVE> moves = new EnumMap<>(Constants.GHOST.class);
        int pacmanLocation = game.getPacmanCurrentNodeIndex();
        for (Constants.GHOST ghost : Constants.GHOST.values()) { //For each ghost...
            int index = game.getGhostCurrentNodeIndex(ghost); //Get position of ghost in maze
            MOVE previousMove = game.getGhostLastMoveMade(ghost); //Get last movement of ghost
            if (game.isJunction(index)) { //If it's a junction, ghost might choose to turn
                try {
                    MOVE move = (game.isGhostEdible(ghost))
                            ? game.getApproximateNextMoveAwayFromTarget(index, pacmanLocation, previousMove, Constants.DM.PATH) //If ghost is edible, it moves away from PM
                            : game.getNextMoveTowardsTarget(index, pacmanLocation, previousMove, Constants.DM.PATH); //If ghost is unedible, it moves towards PM
                    moves.put(ghost, move);
                } catch (NullPointerException npe) {
                    System.err.println("PacmanLocation: " + pacmanLocation + " Maze Index: " + game.getMazeIndex() + " Last Move: " + previousMove);
                }
            } else { //If it's not junction, ghost will continue forward (because reversing is not possible)
                moves.put(ghost, previousMove);
            }
        }
        return moves;
    }

    private boolean isFullyExpanded() {
        return children != null && children.length == expandedChildren;
    }

    public Node expand(Game game) {
        // Select random unselected child
        int index = -1; //Default value to avoid errors from not initializing
        Random random = new Random();
        double bestScore = -Double.MAX_VALUE;
        for (int i = 0; i < children.length; i++) {
            if (children[i] == null) { //Check that child #i hasn't been expanded before (just in case...)
                double score = random.nextDouble();
                if (score > bestScore) {
                    index = i;
                    bestScore = score;
                }
            }
        }
        expandedChildren++;

        game.advanceGame(legalMoves[index], getBasicGhostMoves(game)); //Assume ghosts' behaviour is simple

        MOVE[] childMoves;
        if (parent == null) { //This means it is the root node
            childMoves = getAllLegalMoves(game);
        } else { //If it's not the root, exclude backward movement
            childMoves = getLegalMovesNotIncludingBackwards(game);
            //childMoves = getAllLegalMoves(game);
        }

        Node child = new Node(this, legalMoves[index], childMoves);
        children[index] = child;
        return child;
    }

    public Node selectBestChild() {
        Node bestChild = null;
        double bestScore = -Double.MAX_VALUE;
        for (Node child : children) {
            double score = child.calculateChildScore(); //The core of the selection
            if (score > bestScore) {
                bestChild = child;
                bestScore = score;
            }
        }
        return bestChild;
    }


    private double calculateChildScore() { //Should play around with this and try to find the best formula (Doesn't matter, decided by calculateGameScore)
        return (score / visits) + Math.sqrt(2 * Math.log((parent.visits + 1) / visits));
    }

    private double calculateGameScore(Game game) { //Should play around with this and try to find the best formula
        return game.getScore() + game.getTotalTime()*0 + (00000 * game.getCurrentLevel());

    }

    public MOVE selectBestMove(Game game) {
        Node bestChild = null;
        double bestScore = -Double.MAX_VALUE;
        for (Node child : children) {
            if (child == null) continue;
            double score = child.score;
            if (score > bestScore) {
                bestChild = child;
                bestScore = score;
            }
        }

        if (bestChild == null) {
            return MOVE.NEUTRAL;
        } else {
            return getNextMove(bestChild, game);
        }

        //return bestChild == null ? MOVE.NEUTRAL : getNextMove(bestChild, game);
        //return bestChild == null ? MOVE.NEUTRAL : bestChild.prevMove;
    }

    public MOVE getNextMove(Node child, Game game) {
        //Trying new stuff
        int[] ghostEdibleTimeMock;
        MOVE nextMove = child.prevMove;
        GhostPredictionsFast predictions;
        ghostEdibleTimeMock = new int[Constants.GHOST.values().length];
        Arrays.fill(ghostEdibleTimeMock, -1);
        predictions = new GhostPredictionsFast(game.getCurrentMaze()); //Predicts ghosts' movement
        predictions.preallocate();
        for (Constants.GHOST ghost : Constants.GHOST.values()) {
            if (ghostEdibleTimeMock[ghost.ordinal()] != -1) {
                ghostEdibleTimeMock[ghost.ordinal()]--;
            }

            int ghostIndex = game.getGhostCurrentNodeIndex(ghost);
            if (ghostIndex != -1) { //We see the ghost!
                predictions.observe(ghost, ghostIndex, game.getGhostLastMoveMade(ghost));
                ghostEdibleTimeMock[ghost.ordinal()] = game.getGhostEdibleTime(ghost);
                //Now we'll get the move we need to do to get away from the ghost we see
                MOVE ghostDirOpp = game.getNextMoveAwayFromTarget(game.getPacmanCurrentNodeIndex(), game.getGhostCurrentNodeIndex(ghost), DM.PATH);
                if (Arrays.stream(getAllLegalMoves(game)).anyMatch(ghostDirOpp::equals) && game.getGhostEdibleTime(ghost) <= 0) { //If the move away from ghost is legal AND ghost is not edible
                    nextMove = ghostDirOpp; //Then we force it
                    //System.out.println("I'm running from a ghost now.");

                    //System.out.println(ghostDirOpp);
                }
            }

        }
        return nextMove;
    }
}


    /*
    //Trying new stuff
    int[] ghostEdibleTimeMock;
    GhostPredictionsFast predictions;
    ghostEdibleTimeMock = new int[Constants.GHOST.values().length];
        Arrays.fill(ghostEdibleTimeMock, -1);
    predictions = new GhostPredictionsFast(game.getCurrentMaze()); //Predicts ghosts' movement
        predictions.preallocate();
        for (Constants.GHOST ghost : Constants.GHOST.values()) {
        if (ghostEdibleTimeMock[ghost.ordinal()] != -1) {
            ghostEdibleTimeMock[ghost.ordinal()]--;
        }

        int ghostIndex = game.getGhostCurrentNodeIndex(ghost);
        if (ghostIndex != -1) { //We see the ghost!
            predictions.observe(ghost, ghostIndex, game.getGhostLastMoveMade(ghost));
            ghostEdibleTimeMock[ghost.ordinal()] = game.getGhostEdibleTime(ghost);
            //Now we'll get the move we need to do to get away from the ghost we see
            MOVE ghostDirOpp = game.getNextMoveAwayFromTarget(game.getPacmanCurrentNodeIndex(), game.getGhostCurrentNodeIndex(ghost), DM.PATH);
            if (Arrays.stream(getAllLegalMoves(game)).anyMatch(ghostDirOpp::equals)) { //If the move away from ghost is legal
                this.legalMoves = new MOVE[] {ghostDirOpp}; //Then we force it by saying it is the ONLY legal move right now
                System.out.println("I'm running from a ghost now.");
                System.out.println(ghostDirOpp);
            }
            else { //If it isn't, for example if there is a wall
                System.out.println("Can't escape.");
                this.legalMoves = getLegalMovesNotIncludingBackwards(game); //Then default to other moves
            }
        }
        else {
            //System.out.println("Ain't running.");
            this.legalMoves = getLegalMovesNotIncludingBackwards(game);
            //this.legalMoves = getAllLegalMoves(game);
        }
    }
    */


