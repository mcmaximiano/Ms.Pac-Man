package entrants.pacman.mcmaximiano;

import pacman.controllers.PacmanController;
import pacman.game.Constants;
import pacman.game.Constants.MOVE;
import pacman.game.Game;
import prediction.GhostLocation;
import prediction.PillModel;
import prediction.fast.GhostPredictionsFast;

import java.util.Arrays;
import java.util.List;
import java.util.LinkedList;


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
    protected int maxTreeDepth;
    protected int maxRolloutDepth;

    public MyPacMan() {
        ghostEdibleTime = new int[Constants.GHOST.values().length];
        maxTreeDepth = 40;
        maxRolloutDepth = 200;
    }

    public MOVE getMove(Game game, long timeDue) {

        //We need a model of the game! Do this:
        if (currentMaze != game.getCurrentMaze()){
            currentMaze = game.getCurrentMaze();
            predictions = null;
            pillModel = null;
            System.out.println("New Maze");
            Arrays.fill(ghostEdibleTime, -1);
        }

        mostRecentGame = game;

        if (game.gameOver()) return null;

        if (game.wasPacManEaten()) { //This means PM was eaten in the last time step and just respawned
            predictions = null;
        }

        if (predictions == null) {
            predictions = new GhostPredictionsFast(game.getCurrentMaze());
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

        // Select

        // Expand

        // Play-out

        // Back-propagate



        predictions.update();

        // myMove = best move
        return myMove;
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
        this.children = new Node[legalMoves.length];
    }

    public Node (Node parent, MOVE previousMove, MOVE[] legalMoves) { //Constructor for child node
        this.MyPacMan = parent.MyPacMan;
        this.parent = parent;
        this.treeDepth = parent.treeDepth + 1;
        this.prevMove = previousMove;
        this.legalMoves = legalMoves;
        this.children = new Node[legalMoves.length];
    }

    /* MCTS methods */
    public Node select (Game game){
        Node current = this;
        while (current.treeDepth < MyPacMan.maxTreeDepth && !game.gameOver()){
            if(current.isFullyExpanded()){
                current = current.selectBestChild();
                game.advanceGame(current.prevMove, getBasicGhostMoves(game)); //Assume ghosts' behaviour is simple
                //game.advanceGame(current.prevMove, getBasicGhostMoves(game)); //Assume ghosts' behaviour is random
            }
            else {
                current = current.expand(game);
                game.advanceGame(current.prevMove, getBasicGhostMoves(game)); //Assume ghosts' behaviour is simple
                //game.advanceGame(current.prevMove, getBasicGhostMoves(game)); //Assume ghosts' behaviour is random
                return current;
            }
        }
        return current;
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
}