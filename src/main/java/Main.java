
/* Choose Ghosts here */
import examples.StarterGhostComm.Blinky;
import examples.StarterGhostComm.Inky;
import examples.StarterGhostComm.Pinky;
import examples.StarterGhostComm.Sue;

/* Choose PacMan here */
import examples.StarterISMCTS.InformationSetMCTSPacMan;
import entrants.pacman.mcmaximiano.MyPacMan;
//import examples.StarterPacMan.MyPacMan;


import pacman.Executor;
import pacman.controllers.IndividualGhostController;
import pacman.controllers.MASController;
import pacman.game.Constants.*;

import java.util.EnumMap;

//Note: Using LOS type of po

/**
 * Created by pwillic on 06/05/2016.
 */
public class Main {

    public static void main(String[] args) {

        Executor executor = new Executor.Builder()
                .setVisual(true)
                .setTickLimit(4000)
                .build();

        EnumMap<GHOST, IndividualGhostController> controllers = new EnumMap<>(GHOST.class);

        controllers.put(GHOST.INKY, new Inky());
        controllers.put(GHOST.BLINKY, new Blinky());
        controllers.put(GHOST.PINKY, new Pinky());
        controllers.put(GHOST.SUE, new Sue());

        /* For Developing MyPacMan */
        executor.runGameTimed(new MyPacMan(), new MASController(controllers));
        /** /
        executor.runGameTimed(new InformationSetMCTSPacMan(), new MASController(controllers));

        /* For mass testing * /
        System.out.println(executor.runExperiment(new MyPacMan(), new MASController(controllers), 40, "Starter PM")[0].toString());
        /** /
        System.out.println(executor.runExperiment(new InformationSetMCTSPacMan(), new MASController(controllers), 5, "ISMCTS PM")[0].toString());

        /**/

        System.out.println("GAME OVER");

    }
}
