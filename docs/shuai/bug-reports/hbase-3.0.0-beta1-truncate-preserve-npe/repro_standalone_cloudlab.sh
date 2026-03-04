#!/usr/bin/env bash
set -euo pipefail

WORKDIR="${1:-/users/swang516/xlab/rupfuzz/upfuzz/fuzzing_storage/hbase/hbase-2.6.4/hbase-3.0.0-beta-1/2026-03-02-21-08-19-aqGPEbLC}"
TABLE="${TABLE:-uuid5b0d2dc1a8384316a687a9e9f1a037d4}"
CTRL_TABLE="${CTRL_TABLE:-uuid0383c3f4e56146978695b18b8fd9803f}"
ATTEMPTS="${ATTEMPTS:-20}"

NODE0="hbase-hbase-2.6.4_hbase-3.0.0-beta-1_aqGPEbLC_N0"
NODE1="hbase-hbase-2.6.4_hbase-3.0.0-beta-1_aqGPEbLC_N1"

need() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing: $1" >&2; exit 1; }
}

need docker

[[ -d "$WORKDIR" ]] || { echo "Missing WORKDIR: $WORKDIR" >&2; exit 1; }
[[ -f "$WORKDIR/docker-compose.yaml" ]] || { echo "Missing docker-compose.yaml in $WORKDIR" >&2; exit 1; }

# Disable collector-coupled coverage env for standalone replay (no Upfuzz collector needed)
for envf in "$WORKDIR/persistent/node_0/env.sh" "$WORKDIR/persistent/node_1/env.sh"; do
  [[ -f "$envf" ]] || { echo "Missing env file: $envf" >&2; exit 1; }
  cp -n "$envf" "${envf}.bak_standalone" || true
  sed -i 's|^export JAVA_TOOL_OPTIONS=.*$|export JAVA_TOOL_OPTIONS=""|' "$envf"
  sed -i 's|^export ENABLE_NET_COVERAGE=.*$|export ENABLE_NET_COVERAGE=false|' "$envf"
  sed -i 's|^export ENABLE_NETWORK_TRACE=.*$|export ENABLE_NETWORK_TRACE=false|' "$envf"
done

OUT_DIR="$WORKDIR/standalone-repro-$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT_DIR"

echo "[1/5] Restarting replay cluster"
(
  cd "$WORKDIR"
  docker compose down -v >/dev/null 2>&1 || true
  docker compose up -d >/dev/null
)

echo "[2/5] Waiting for HMaster/HRegionServer"
for _ in $(seq 1 60); do
  m0="$(docker exec "$NODE0" bash -lc "jps -l | grep -c 'org.apache.hadoop.hbase.master.HMaster' || true")"
  r1="$(docker exec "$NODE1" bash -lc "jps -l | grep -c 'org.apache.hadoop.hbase.regionserver.HRegionServer' || true")"
  if [[ "$m0" -ge 1 && "$r1" -ge 1 ]]; then
    break
  fi
  sleep 2
done

docker exec "$NODE0" bash -lc 'jps -l' > "$OUT_DIR/node0_jps.txt" 2>&1 || true
docker exec "$NODE1" bash -lc 'jps -l' > "$OUT_DIR/node1_jps.txt" 2>&1 || true

echo "[3/5] Attempting NPE reproduction on node0 (${ATTEMPTS} attempts)"
hit=0
for i in $(seq 1 "$ATTEMPTS"); do
  out="$OUT_DIR/repro_uuid5b_n0_attempt_${i}.out"
  docker exec "$NODE0" bash -lc "timeout 120 bash -lc \"echo \\\"truncate_preserve \\\\\\\"$TABLE\\\\\\\"\\\" | /hbase/hbase-3.0.0-beta-1/bin/hbase shell -n\"" > "$out" 2>&1 || true
  grep -nE 'NullPointerException|RawAsyncHBaseAdmin|tableState|Truncating|Failed contacting masters|RetriesExhaustedException' "$out" \
    > "$OUT_DIR/repro_uuid5b_n0_attempt_${i}_key_lines.txt" || true
  if grep -q 'RawAsyncHBaseAdmin.lambda\$isTableEnabled' "$out"; then
    hit=1
    cp "$out" "$OUT_DIR/repro_uuid5b_n0.out"
    cp "$OUT_DIR/repro_uuid5b_n0_attempt_${i}_key_lines.txt" "$OUT_DIR/repro_uuid5b_n0_key_lines.txt"
    echo "NPE_HIT_ATTEMPT=$i" > "$OUT_DIR/summary.txt"
    break
  fi
done

echo "[4/5] Running control command on node1: truncate_preserve \"$CTRL_TABLE\""
docker exec "$NODE1" bash -lc "timeout 120 bash -lc \"echo \\\"truncate_preserve \\\\\\\"$CTRL_TABLE\\\\\\\"\\\" | /hbase/hbase-3.0.0-beta-1/bin/hbase shell -n\"" > "$OUT_DIR/repro_uuid038_n1.out" 2>&1 || true

echo "[5/5] Finalizing result"
if [[ "$hit" -eq 1 ]]; then
  echo "BUG REPRODUCED: RawAsyncHBaseAdmin NPE observed"
  echo "Artifacts: $OUT_DIR"
  exit 0
fi

echo "NPE_NOT_HIT_ATTEMPTS=$ATTEMPTS" > "$OUT_DIR/summary.txt"
echo "NPE signature not found in $ATTEMPTS attempts. Check: $OUT_DIR" >&2
exit 2
