"""Stitch restart chunks into one history and decide whether to stop the chain.

    python3 stitch_restarts.py <chain_dir> [--stop-on neck_ratio,surface_length] [--tol 0.005]

<chain_dir>/chunks.txt lists each chunk's metrics CSV in time order (first line =
the initial t = 0 run). Writes <chain_dir>/combined/metrics.csv and appends a
line to <chain_dir>/chain_log.tsv.

Stop (exit code 10) when every --stop-on metric changed by less than tol
(relative to its first value) over EACH of the last two restart chunks.
Chunks double in length, so this means a plateau in log(time).

Choose the stop metrics with care: in sintering, pore area can stay flat for a
long time while necks are still growing (early stage), so stopping on pore area
alone ends the run too early. Neck ratio and free-surface length track the
free energy and only plateau when the microstructure has stopped evolving.
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import comsol_csv  # noqa: E402


def stitch(files):
    hist, bounds = {}, []
    for f in files:
        d = comsol_csv.read(f)
        # a restart repeats the previous end point as its first sample
        skip = 1 if hist and abs(d["time"][0] - hist["time"][-1]) < 1e-9 else 0
        start = len(hist.get("time", [])) - skip
        for name, ys in d.items():
            hist.setdefault(name, []).extend(ys[skip:])
        bounds.append((start, len(hist["time"]) - 1))
    return hist, bounds


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("chain_dir")
    ap.add_argument("--stop-on", default="neck_ratio,surface_length")
    ap.add_argument("--tol", type=float, default=0.005)
    args = ap.parse_args()
    chain = Path(args.chain_dir)
    files = [Path(l.strip()) for l in (chain / "chunks.txt").read_text().splitlines() if l.strip()]
    hist, bounds = stitch(files)
    (chain / "combined").mkdir(exist_ok=True)
    comsol_csv.write(chain / "combined" / "metrics.csv", hist, "stitched by stitch_restarts.py")

    metrics = [m.strip() for m in args.stop_on.split(",") if m.strip()]
    restarts = bounds[1:]                      # exclude the initial run
    changes = {}
    for m in metrics:
        ys, ref = hist[m], abs(hist[m][0]) or 1.0
        changes[m] = [abs(ys[b] - ys[a]) / ref for a, b in restarts]
    plateau = len(restarts) >= 2 and all(max(c[-2:]) < args.tol for c in changes.values())

    t = hist["time"]
    summary = "  ".join(f"{m}: {c[-1]:.4f}" for m, c in changes.items() if c)
    print(f"chunks={len(files)} t_end={t[-1]:g}  last-chunk relative change  {summary}  (tol {args.tol})")
    with (chain / "chain_log.tsv").open("a") as f:
        f.write(f"{len(files)}\t{t[-1]:g}\t{summary}\t{'plateau' if plateau else 'running'}\n")
    if plateau:
        print("STOP: all stop metrics have plateaued")
        sys.exit(10)


if __name__ == "__main__":
    main()
