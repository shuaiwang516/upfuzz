#!/bin/bash
# Usage: ./check_cass_19623.sh [failure_dir]
# Default: failure

FAILURE_DIR="${1:-failure}"

f=$(grep -rl "illegal RT bounds sequence" "$FAILURE_DIR" | sort -t '_' -k2,2n | head -n 1)

if [[ -n "$f" && -f "$f" ]]; then
  echo "[OK]   bug is triggered"
  echo "[FILE] $f"
else
  echo "[FAIL] bug is not triggered"
fi