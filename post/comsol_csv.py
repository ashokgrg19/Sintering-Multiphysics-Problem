"""Read/write COMSOL table exports (CSV with '%' header lines). Standard library only."""

import re


def read(path):
    """Return {column: [floats]}; the last '%' line holds the column names.

    Names are lower-cased with any trailing ' (unit)' removed; the first column
    is called 'time'.
    """
    header, rows = None, []
    with open(path) as f:
        for line in f:
            if line.startswith("%"):
                header = line.lstrip("% ").rstrip("\n").split(",")
            elif line.strip():
                rows.append([float(v) for v in line.split(",")])
    if header is None:
        raise ValueError(f"{path}: no '%' header line")
    names = [re.sub(r"\s*\(.*\)$", "", h.strip()).lower() for h in header]
    names[0] = "time"
    return {n: [r[i] for r in rows] for i, n in enumerate(names)}


def write(path, cols, title="written by comsol_csv.py"):
    names = list(cols)
    with open(path, "w") as f:
        f.write(f"% {title}\n")
        f.write("% " + ",".join("Time (s)" if n == "time" else n for n in names) + "\n")
        for row in zip(*(cols[n] for n in names)):
            f.write(",".join(repr(float(v)) for v in row) + "\n")
