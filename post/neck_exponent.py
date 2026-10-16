"""Neck-growth exponent and calibration comparison across runs.

    python3 neck_exponent.py run1.csv run2.csv ... [--col neck_ratio] [--window 0.08,0.30]

Fits (x/D)^n ~ t on log-log axes inside the x/D window and reports n, the time
to reach x/D = 0.3 and the solid-area drift. Use it to validate a speed-up
(smaller R/delta, faster diffusion relative to interface relaxation): the
speed-up is acceptable when n stays the same as in the reference run. A drop
of n toward ~2 indicates interface-controlled kinetics, i.e. the phase-field
interface no longer stays at local equilibrium.
"""

import argparse
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import comsol_csv  # noqa: E402


def fit_exponent(t, xd, lo, hi):
    pts = [(math.log(a), math.log(b)) for a, b in zip(t, xd) if a > 0 and lo <= b <= hi]
    if len(pts) < 3:
        return float("nan"), len(pts)
    mx = sum(p[0] for p in pts) / len(pts)
    my = sum(p[1] for p in pts) / len(pts)
    slope = sum((p[0] - mx) * (p[1] - my) for p in pts) / sum((p[0] - mx) ** 2 for p in pts)
    return 1 / slope, len(pts)


def time_to(t, y, target):
    for i in range(1, len(t)):
        if y[i - 1] < target <= y[i]:
            return t[i - 1] + (target - y[i - 1]) * (t[i] - t[i - 1]) / (y[i] - y[i - 1])
    return float("nan")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", nargs="+")
    ap.add_argument("--col", default="neck_ratio")
    ap.add_argument("--window", default="0.08,0.30")
    args = ap.parse_args()
    lo, hi = (float(v) for v in args.window.split(","))
    print(f"{'run':30s} {'n':>6s} {'pts':>4s} {'t(x/D=0.3)':>11s} {'x/D end':>8s} {'mass drift %':>12s}")
    for f in args.csv:
        d = comsol_csv.read(f)
        t, xd = d["time"], d[args.col]
        n, k = fit_exponent(t, xd, lo, hi)
        drift = ""
        if "solid_area" in d:
            m = d["solid_area"]
            drift = f"{max(abs(v / m[0] - 1) for v in m) * 100:.3f}"
        print(f"{Path(f).parent.name or f:30s} {n:6.2f} {k:4d} {time_to(t, xd, 0.3):11.4g} {xd[-1]:8.3f} {drift:>12s}")


if __name__ == "__main__":
    main()
