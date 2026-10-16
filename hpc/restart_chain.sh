#!/bin/bash
#SBATCH --job-name=chain
#SBATCH --nodes=1
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=16
#SBATCH --mem=64G
#SBATCH --time=3-00:00:00
#SBATCH --output=chain/logs/chunk_%j.out
#SBATCH --error=chain/logs/chunk_%j.err
#
# Self-resubmitting restart chain for long transient COMSOL runs.
# Each job continues the previous solution for one time window
# (comsol/RestartChunk.java), stitches all windows (post/stitch_restarts.py)
# and resubmits itself until the stop criterion is met.
#
# Start:
#   mkdir -p chain/logs
#   PREV_MPH=$PWD/first_run.mph INIT_CSV=$PWD/first_run_metrics.csv \
#   SBATCH_ARGS="-p <partition> -A <account>" sbatch -p <partition> -A <account> restart_chain.sh
# Stop after the current chunk:  touch chain/STOP
#
# Chunk length doubles (CHUNK0, 2*CHUNK0, ...) up to CHUNK_MAX, so the chain is
# uniform in log(time) - matching how sintering kinetics slow down.
set -euo pipefail
cd "$SLURM_SUBMIT_DIR"
COMSOL="${COMSOL:-comsol}"
# repository root; must be given because SLURM runs a spooled copy of this script
HERE="${TOOLS:?set TOOLS=<path to comsol-sintering-hpc-tools>}"
CHAIN="$PWD/chain"
K="${CHAIN_K:-1}"
CHUNK0="${CHUNK0:-50}"; CHUNK_MAX="${CHUNK_MAX:-800}"; MAX_CHUNKS="${MAX_CHUNKS:-12}"
STOP_ON="${STOP_ON:-neck_ratio,surface_length}"   # metrics that must plateau
TOL="${TOL:-0.005}"
: "${PREV_MPH:?}"

chunk=$(( CHUNK0 * (1 << (K - 1)) )); (( chunk > CHUNK_MAX )) && chunk=$CHUNK_MAX
work="$CHAIN/chunk_$(printf %02d "$K")_${SLURM_JOB_ID}"
mkdir -p "$work" "$CHAIN/logs"
if [[ ! -s "$CHAIN/chunks.txt" ]]; then
    : "${INIT_CSV:?first chunk needs INIT_CSV}"
    echo "$INIT_CSV" > "$CHAIN/chunks.txt"
fi

cp "$HERE/comsol/RestartChunk.java" "$work/"
cd "$work"
sed -i "s|static String PREV = \"__PREV_MPH__\";|static String PREV = \"$PREV_MPH\";|
        s|static String WORK = \"__WORK__\";|static String WORK = \"$work\";|
        s|static double CHUNK = 50;|static double CHUNK = $chunk;|" RestartChunk.java
"$COMSOL" compile RestartChunk.java > compile.log 2>&1
for try in $(seq 1 72); do
    "$COMSOL" -np "$SLURM_CPUS_PER_TASK" -tmpdir "$work" -recoverydir "$work" batch -nosave \
        -inputfile "$work/RestartChunk.class" -batchlog "$work/build.log" > launcher.log 2>&1 || true
    grep -q "Could not obtain license" build.log launcher.log 2>/dev/null || break
    sleep 600
done
cat launcher.log
[[ -s chunk.mph && -s metrics.csv ]] || { echo "chunk $K produced no result" >&2; exit 1; }
echo "$work/metrics.csv" >> "$CHAIN/chunks.txt"

set +e
python3 "$HERE/post/stitch_restarts.py" "$CHAIN" --stop-on "$STOP_ON" --tol "$TOL"
rc=$?
set -e
if (( rc == 10 )); then echo "chain finished at chunk $K"
elif [[ -e "$CHAIN/STOP" ]]; then echo "stopped by $CHAIN/STOP"
elif (( K >= MAX_CHUNKS )); then echo "hit MAX_CHUNKS=$MAX_CHUNKS"
else
    cd "$SLURM_SUBMIT_DIR"
    PREV_MPH="$work/chunk.mph" CHAIN_K=$((K + 1)) sbatch ${SBATCH_ARGS:-} "$HERE/hpc/restart_chain.sh"
fi
