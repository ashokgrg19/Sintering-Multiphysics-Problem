#!/bin/bash
#SBATCH --job-name=comsol
#SBATCH --nodes=1
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=16
#SBATCH --mem=64G
#SBATCH --time=1-00:00:00
#SBATCH --output=logs/%x_%j.out
#SBATCH --error=logs/%x_%j.err
#
# Compile and run one COMSOL Java model class in batch mode on a SLURM node.
#
#   JAVA=MyModel.java SET="SOLVE=true;TLIST=range(0,1,100)" sbatch -p <partition> -A <account> run_comsol_job.sh
#
# SET is a ;-separated list of NAME=VALUE pairs. Each one rewrites the static
# literal `static <type> NAME = ...;` in the copied source, because COMSOL's
# Java sandbox blocks System.getenv(). Strings are quoted automatically.
#
# COMSOL licenses are a shared floating pool: if no seat is free the run is
# retried every RETRY_MIN minutes (the node stays allocated while waiting).
set -euo pipefail
cd "$SLURM_SUBMIT_DIR"
COMSOL="${COMSOL:-comsol}"
: "${JAVA:?set JAVA=<Model>.java}"
RETRY_MIN="${RETRY_MIN:-10}"; RETRIES="${RETRIES:-72}"
class="$(basename "${JAVA%.java}")"
work="$PWD/runs/${class}_${SLURM_JOB_ID:-$$}"
mkdir -p "$work" logs
cp "$JAVA" "$work/"
cd "$work"

IFS=';' read -ra pairs <<< "${SET:-}"
for kv in "${pairs[@]}"; do
    [[ -z "$kv" ]] && continue
    name="${kv%%=*}"; value="${kv#*=}"
    if grep -qE "static String $name = " "$class.java"; then value="\"$value\""; fi
    sed -i -E "s|(static [A-Za-z]+ $name = ).*;|\1$value;|" "$class.java"
    grep -nE "static [A-Za-z]+ $name = " "$class.java"
done

"$COMSOL" compile "$class.java" > compile.log 2>&1
[[ -f "$class.class" ]] || { cat compile.log >&2; exit 1; }

for try in $(seq 1 "$RETRIES"); do
    "$COMSOL" -np "$SLURM_CPUS_PER_TASK" -tmpdir "$work" -recoverydir "$work" batch -nosave \
        -inputfile "$work/$class.class" -batchlog "$work/build.log" > launcher.log 2>&1 || true
    grep -q "Could not obtain license" build.log launcher.log 2>/dev/null || break
    echo "no COMSOL license free (try $try/$RETRIES), retrying in $RETRY_MIN min"
    sleep $(( RETRY_MIN * 60 ))
done
cat launcher.log
grep -E "Solution time|Total time" build.log || true
