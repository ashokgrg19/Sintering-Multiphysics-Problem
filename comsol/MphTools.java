/*
 * MphTools.java - small batch utilities for .mph files.
 *
 *   MODE = "java"   export the model as Java source (readable equations/settings)
 *   MODE = "table"  re-evaluate an EvalGlobal node and export its table to CSV
 *
 * Note: model.save(path) with a ".java" name still writes an MPH archive;
 * the two-argument form save(path, "java") is required for source export.
 */

import com.comsol.model.*;
import com.comsol.model.util.*;

public class MphTools {
  static String MODE = "java";
  static String SRC = "__SRC__";
  static String DST = "__DST__";
  static String EVAL = "gev1", TABLE = "tbl1", EXPORT = "exp1";

  public static void main(String[] args) throws java.io.IOException {
    Model model = ModelUtil.load("Model", SRC);
    if (MODE.equals("java")) {
      model.resetHist();          // compact history -> readable source
      model.save(DST, "java");
    } else {
      model.result().table(TABLE).clearTableData();
      model.result().numerical(EVAL).computeResult();
      model.result().numerical(EVAL).setResult();
      model.result().export(EXPORT).set("filename", DST);
      model.result().export(EXPORT).run();
    }
    System.out.println("[mph] " + MODE + " -> " + DST);
  }
}
