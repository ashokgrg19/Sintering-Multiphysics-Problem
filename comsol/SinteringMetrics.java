/*
 * SinteringMetrics.java - add standard sintering metrics to any COMSOL
 * phase-field model with one order parameter per grain.
 *
 * Metrics (global variables, evaluated at every stored time):
 *   pore area / equivalent radius / porosity of a unit cell (named selection)
 *   neck width of each contact: solid length along the contact plane
 *     (line integral over a named boundary selection), plus a co-area
 *     cross-check  W = int 3 phi_i phi_j (|grad phi_i| + |grad phi_j|) dA
 *   grain centroids and centre-to-centre distance of each contact -> compaction
 *   total solid area (mass-conservation check) and free-surface length
 *
 * IMPORTANT: call add(...) BEFORE solving. Variables created after a solve are
 * not known to the stored solution ("Undefined_post_expression") unless the
 * solution is updated with model.sol(tag).updateSolution().
 *
 * Usage from a model-building class:
 *   SinteringMetrics.add(model, "comp1", "geom1",
 *       new String[]{"phi1","phi2","phi3","phi4"}, "psi",
 *       new int[][]{{1,2},{1,3},{2,4},{3,4}},
 *       "geom1_sqCell_dom",                       // unit cell domains
 *       new String[]{"geom1_ls12_bnd", ...},      // contact-plane lines, per pair
 *       "R0");                                    // grain radius expression
 */

import com.comsol.model.*;

public class SinteringMetrics {

  public static void add(Model model, String comp, String geom, String[] phi, String gas,
                         int[][] pairs, String cellSel, String[] neckSel, String radius) {
    ModelNode c = model.component(comp);
    integration(model, comp, geom, "intAll", 2, null);
    integration(model, comp, geom, "intCell", 2, cellSel);
    for (int k = 0; k < pairs.length; k++)
      integration(model, comp, geom, "intN" + tag(pairs[k]), 1, neckSel[k]);

    String V = "varSinter";
    c.variable().create(V);
    c.variable(V).label("Sintering metrics");
    StringBuilder solid = new StringBuilder();
    for (int i = 0; i < phi.length; i++) solid.append(i > 0 ? "+" : "").append(phi[i]);
    set(model, comp, V, "sm_solid", solid.toString(), "Total solid fraction");

    set(model, comp, V, "sm_Acell", "intCell(1)", "Unit cell area");
    set(model, comp, V, "sm_Apore", "intCell(" + gas + ")", "Pore area in unit cell");
    set(model, comp, V, "sm_Rpore", "sqrt(sm_Apore/pi)", "Pore equivalent radius");
    set(model, comp, V, "sm_porosity", "sm_Apore/sm_Acell", "Cell porosity");

    for (int i = 0; i < phi.length; i++) {
      String p = phi[i], n = "" + (i + 1);
      set(model, comp, V, "sm_A" + n, "intAll(" + p + ")", "Area of grain " + n);
      set(model, comp, V, "sm_xc" + n, "intAll(x*" + p + ")/sm_A" + n, "Centroid x, grain " + n);
      set(model, comp, V, "sm_yc" + n, "intAll(y*" + p + ")/sm_A" + n, "Centroid y, grain " + n);
    }
    set(model, comp, V, "sm_Asolid", "intAll(sm_solid)", "Total solid area (mass check)");

    StringBuilder wSum = new StringBuilder(), dSum = new StringBuilder();
    for (int k = 0; k < pairs.length; k++) {
      int i = pairs[k][0], j = pairs[k][1];
      String t = tag(pairs[k]), pi = phi[i - 1], pj = phi[j - 1];
      set(model, comp, V, "sm_W" + t, "intN" + t + "(sm_solid)", "Neck width " + i + "-" + j);
      set(model, comp, V, "sm_Wca" + t, "intAll(3*" + pi + "*" + pj + "*(sqrt(d(" + pi + ",x)^2+d(" + pi
          + ",y)^2)+sqrt(d(" + pj + ",x)^2+d(" + pj + ",y)^2)))", "Neck width " + i + "-" + j + " (co-area)");
      set(model, comp, V, "sm_d" + t, "sqrt((sm_xc" + j + "-sm_xc" + i + ")^2+(sm_yc" + j + "-sm_yc" + i + ")^2)",
          "Centre distance " + i + "-" + j);
      wSum.append(k > 0 ? "+" : "").append("sm_W").append(t);
      dSum.append(k > 0 ? "+" : "").append("sm_d").append(t);
    }
    set(model, comp, V, "sm_W_avg", "(" + wSum + ")/" + pairs.length, "Average neck width");
    set(model, comp, V, "sm_neck_ratio", "sm_W_avg/(2*(" + radius + "))", "Neck ratio x/D");
    set(model, comp, V, "sm_d_avg", "(" + dSum + ")/" + pairs.length, "Average centre distance");
    set(model, comp, V, "sm_S", "intAll(6*" + gas + "*(1-" + gas + ")*sqrt(d(" + gas + ",x)^2+d(" + gas + ",y)^2))",
        "Free-surface length");
  }

  static String tag(int[] p) { return "" + p[0] + p[1]; }

  static void integration(Model model, String comp, String geom, String tag, int dim, String sel) {
    model.component(comp).cpl().create(tag, "Integration");
    model.component(comp).cpl(tag).selection().geom(geom, dim);
    if (sel == null) model.component(comp).cpl(tag).selection().all();
    else model.component(comp).cpl(tag).selection().named(sel);
  }

  static void set(Model model, String comp, String v, String name, String expr, String descr) {
    model.component(comp).variable(v).set(name, expr, descr);
  }

  /** EvalGlobal + table + CSV export of the main metrics; evaluate after the solve. */
  public static void exportTable(Model model, String solTag, String csvPath, int nPairs) {
    model.result().dataset().create("dsSinter", "Solution");
    model.result().dataset("dsSinter").set("solution", solTag);
    model.result().table().create("tblSinter", "Table");
    model.result().numerical().create("gevSinter", "EvalGlobal");
    model.result().numerical("gevSinter").set("data", "dsSinter");
    String[][] cols = {{"sm_Apore", "pore_area"}, {"sm_Rpore", "pore_eq_radius"}, {"sm_porosity", "porosity"},
        {"sm_W_avg", "neck_width"}, {"sm_neck_ratio", "neck_ratio"}, {"sm_d_avg", "center_dist"},
        {"sm_Asolid", "solid_area"}, {"sm_S", "surface_length"}};
    for (int i = 0; i < cols.length; i++) {
      model.result().numerical("gevSinter").setIndex("expr", cols[i][0], i);
      model.result().numerical("gevSinter").setIndex("descr", cols[i][1], i);
    }
    model.result().numerical("gevSinter").set("table", "tblSinter");
    model.result().numerical("gevSinter").computeResult();
    model.result().numerical("gevSinter").setResult();
    model.result().export().create("expSinter", "Table");
    model.result().export("expSinter").set("table", "tblSinter");
    model.result().export("expSinter").set("filename", csvPath);
    model.result().export("expSinter").run();
  }
}
