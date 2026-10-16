"""Multi-panel time-series plot of a COMSOL table export, written as SVG.

    python3 plot_timeseries_svg.py metrics.csv out.svg [col1,col2 col3 ...]

Each positional group after the output file is one panel; commas put several
columns in the same panel. Without groups, every column gets its own panel.
Standard library only, so it runs on cluster nodes without matplotlib.
"""

import sys
from html import escape
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import comsol_csv  # noqa: E402

COLORS = ["#1676a8", "#d9534f", "#2e8b57", "#e69500", "#7b4fa0"]
PW, PH = 590, 300


def panel(parts, px, py, title, t, series):
    x0, x1, y0, y1 = px + 70, px + PW - 20, py + 45, py + PH - 47
    vals = [v for _, ys in series for v in ys]
    lo, hi = min(vals), max(vals)
    pad = (hi - lo) * 0.08 if hi > lo else max(abs(hi) * 0.08, 1e-12)
    lo, hi = lo - pad, hi + pad
    tx = lambda v: x0 + (v - t[0]) / ((t[-1] - t[0]) or 1) * (x1 - x0)
    ty = lambda v: y1 - (v - lo) / (hi - lo) * (y1 - y0)
    parts.append(f'<text x="{x0}" y="{py + 25}" class="label">{escape(title)}</text>')
    for k in range(5):
        yy, xx = y1 - k * (y1 - y0) / 4, x0 + k * (x1 - x0) / 4
        parts.append(f'<line x1="{x0}" y1="{yy:.1f}" x2="{x1}" y2="{yy:.1f}" class="grid"/>')
        parts.append(f'<text x="{x0 - 8}" y="{yy + 4:.1f}" class="tick" text-anchor="end">{lo + k * (hi - lo) / 4:.3g}</text>')
        parts.append(f'<text x="{xx:.1f}" y="{y1 + 18}" class="tick" text-anchor="middle">{t[0] + k * (t[-1] - t[0]) / 4:.3g}</text>')
    parts.append(f'<line x1="{x0}" y1="{y1}" x2="{x1}" y2="{y1}" class="axis"/>')
    parts.append(f'<line x1="{x0}" y1="{y0}" x2="{x0}" y2="{y1}" class="axis"/>')
    for k, (name, ys) in enumerate(series):
        c = COLORS[k % len(COLORS)]
        pts = " ".join(f"{tx(a):.1f},{ty(b):.1f}" for a, b in zip(t, ys))
        parts.append(f'<polyline points="{pts}" fill="none" stroke="{c}" stroke-width="2.2"/>')
        if len(series) > 1:
            parts.append(f'<text x="{x1 - 4}" y="{y0 + 14 + 14 * k}" class="tick" text-anchor="end" style="fill:{c}">{escape(name)}</text>')
    parts.append(f'<text x="{(x0 + x1) / 2:.1f}" y="{y1 + 38}" class="tick" text-anchor="middle">time</text>')


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    data = comsol_csv.read(sys.argv[1])
    groups = [g.split(",") for g in sys.argv[3:]] or [[c] for c in data if c != "time"]
    t = data["time"]
    rows = (len(groups) + 1) // 2
    W, H = 1240, 75 + rows * 320
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
             '<rect width="100%" height="100%" fill="#ffffff"/>',
             '<style>text{font-family:Arial,sans-serif;fill:#263238}.title{font-size:22px;font-weight:bold}'
             '.label{font-size:16px;font-weight:bold}.tick{font-size:11px;fill:#607d8b}'
             '.grid{stroke:#dfe6eb;stroke-width:1}.axis{stroke:#70838e;stroke-width:1.2}</style>',
             f'<text x="45" y="40" class="title">{escape(Path(sys.argv[1]).name)}</text>']
    for k, g in enumerate(groups):
        panel(parts, 45 + (k % 2) * 610, 60 + (k // 2) * 320, " / ".join(g), t, [(c, data[c]) for c in g])
    parts.append("</svg>")
    Path(sys.argv[2]).write_text("\n".join(parts))
    print(f"wrote {sys.argv[2]}")


if __name__ == "__main__":
    main()
