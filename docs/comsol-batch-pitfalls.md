# COMSOL batch / Java API pitfalls on an HPC cluster

Lessons from running phase-field sintering models (COMSOL 6.3, Java API,
SLURM). Each item cost at least one failed job.

## 1. `System.getenv()` is blocked
COMSOL runs compiled model classes under a Java security manager:

```
java.security.AccessControlException: access denied ("java.lang.RuntimePermission" "getenv.PP_SOLVE")
```

**Fix:** keep run switches as plain literals (`static boolean SOLVE = true;`)
and have the launcher rewrite them with `sed` before `comsol compile`
(`hpc/run_comsol_job.sh` does this generically via `SET="NAME=value;..."`).

## 2. Variables created after the solve are invisible to results
Adding global variables (post-processing metrics) to a model *after*
`runNoGen()` and then evaluating them gives `Undefined_post_expression`,
and every table silently ends up with only a time column.

**Fix:** create all metric variables and coupling operators **before** the
solve, or call `model.sol("sol1").updateSolution()` before evaluating.

## 3. Relative paths follow the loaded model, not the working directory
After `ModelUtil.load(...)`, an export filename like `./metrics.csv`
resolves against the folder the `.mph` was loaded from. A restart job
therefore overwrote the previous run's CSV.

**Fix:** use absolute paths, or call `model.modelPath(workDir)` right after
loading.

## 4. Saving Java source
`model.save("model.java")` still writes an MPH archive. Use
`model.save(path, "java")` (after `model.resetHist()` for a compact file).

## 5. Default time stepping makes restarted runs disagree
With default settings the BDF solver's step is effectively capped at about
1/10 of the requested time window, and the relative tolerance is 0.01.
A short initial run (window 2, steps 0.2) restarted with a long window
(window 50, steps 5) produced a visible kink at the restart time: the
quantities being plotted changed by ~0.1 %, far below the 1 % tolerance, so
the 25x larger step legitimately changed the trajectory.

**Fix:** set the stepping explicitly and identically in every run:

```java
model.sol("sol1").feature("t1").set("rtol", "1e-4");
model.sol("sol1").feature("t1").set("maxstepconstraintbdf", "const");
model.sol("sol1").feature("t1").set("maxstepbdf", "1");
```

## 6. Batch mode auto-saves a second copy
`comsol batch -inputfile X.class` saves `X_Model.mph` next to whatever the
class saves itself. For large transient models that doubles disk use per run.
**Fix:** pass `-nosave` and save explicitly from the class.

## 7. Licenses are a shared floating pool
`Could not obtain license ... Licensed number of users already reached`
depends on what every other user of the license server is running, so the
number of runs you can start varies through the day.
**Fix:** retry inside the job (the launchers here retry every 10 min). The
node stays allocated while waiting, so give jobs enough wall time for both
the wait and the solve.

## 8. Restarting from the last time step
To continue a transient solution in a new window without re-solving:

```java
model.sol(SOL).copySolution("solInit");            // keep the old solution
model.sol(SOL).feature("v1").set("initmethod", "sol");
model.sol(SOL).feature("v1").set("initsol", "solInit");
model.sol(SOL).feature("v1").set("solnum", "last");
// set the new tlist on the study and on t1, then runNoGen()
model.sol().remove("solInit");                     // after the solve
```

`model.sol(SOL).getPVals()` returns the stored times, so the last one gives
the restart time. See `comsol/RestartChunk.java`.

## 9. Normalized metrics need t = 0
`at(0, expr)` in a results table only works if the dataset contains t = 0.
Restarted solutions do not, so normalize after stitching the chunks
(`post/stitch_restarts.py`) instead of inside COMSOL.
