/*
 * RestartChunk.java - continue a transient COMSOL solution for one time window.
 *
 * Loads a solved .mph, takes the LAST stored time step of the given solver
 * sequence as the initial state, solves [t_last, t_last + CHUNK] with the
 * model's own solver settings, exports one results table to CSV and saves the
 * model for the next chunk. Used by hpc/restart_chain.sh.
 *
 * The run switches are plain literals because COMSOL's Java sandbox blocks
 * System.getenv(); the launcher rewrites them with sed before compiling.
 *
 *   comsol compile RestartChunk.java
 *   comsol batch -nosave -inputfile RestartChunk.class
 */

import com.comsol.model.*;
import com.comsol.model.util.*;

public class RestartChunk {

  static String PREV = "__PREV_MPH__";    // solved model to continue from
  static String WORK = "__WORK__";        // absolute output folder
  static double CHUNK = 50;               // length of this time window
  static int NOUT = 25;                   // stored time steps in the window
  static String STUDY = "std1";           // transient study
  static String SOL = "sol1";             // its solver sequence
  static String EVAL = "gev1";            // EvalGlobal node to evaluate ("" = none)
  static String TABLE = "tbl1";           // table that EVAL writes to
  static String EXPORT = "exp1";          // table export node
  static String MAXSTEP = "1";            // fixed BDF max step (see docs/comsol-batch-pitfalls.md)
  static String NAME = "chunk";
  static final String INIT = "solInit";

  public static void main(String[] args) throws java.io.IOException {
    System.out.println("[chunk] loading " + PREV);
    Model model = ModelUtil.load("Model", PREV);
    // relative paths would otherwise resolve against the LOADED model's folder
    model.modelPath(WORK);

    double[] tp = model.sol(SOL).getPVals();
    double t0 = tp[tp.length - 1];
    double t1 = t0 + CHUNK;
    String tl = "range(" + t0 + "," + (CHUNK / NOUT) + "," + t1 + ")";
    System.out.println("[chunk] previous solution t = " + tp[0] + " .. " + t0 + "; solving " + tl);

    // keep the previous solution as the initial-value source, solve into SOL
    for (String s : model.sol().tags()) if (s.equals(INIT)) model.sol().remove(INIT);
    model.sol(SOL).copySolution(INIT);

    model.study(STUDY).feature("time").set("tlist", tl);
    model.sol(SOL).feature("v1").set("initmethod", "sol");
    model.sol(SOL).feature("v1").set("initsol", INIT);
    model.sol(SOL).feature("v1").set("solnum", "last");
    model.sol(SOL).feature("v1").set("clist", new String[]{"{" + tl + "}", "1e-3"});
    model.sol(SOL).feature("t1").set("tlist", tl);
    model.sol(SOL).feature("t1").set("initialstepbdf", "1e-3");
    model.sol(SOL).feature("t1").set("maxstepconstraintbdf", "const");
    model.sol(SOL).feature("t1").set("maxstepbdf", MAXSTEP);

    try {
      model.study(STUDY).runNoGen();
    } catch (Exception e) {
      model.save(WORK + "/" + NAME + "_FAILED.mph");
      throw new IllegalStateException("restart chunk failed", e);
    }
    model.sol().remove(INIT);   // keep the saved file small

    if (!EVAL.isEmpty()) {
      try {
        model.result().table(TABLE).clearTableData();
        model.result().numerical(EVAL).computeResult();
        model.result().numerical(EVAL).setResult();
        model.result().export(EXPORT).set("filename", WORK + "/metrics.csv");
        model.result().export(EXPORT).run();
        System.out.println("[chunk] wrote " + WORK + "/metrics.csv");
      } catch (Exception e) {
        System.out.println("[chunk] metrics export FAILED: " + e.getMessage());
      }
    }
    model.save(WORK + "/" + NAME + ".mph");
    System.out.println("[chunk] saved " + WORK + "/" + NAME + ".mph  t_end=" + t1);
  }
}
