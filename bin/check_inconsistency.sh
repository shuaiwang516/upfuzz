#!/bin/bash

# Usage: ./check_hdfs_17686.sh [failure_dir]
# Default: failure

FAILURE_DIR="${1:-failure}"

source bin/compute_time.sh

# Get the first inconsistency file and extract path up to failure_X
# f=$(find "$FAILURE_DIR" -iname "inconsistency_*" | sort -t '_' -k2,2n | head -n1)

f=$(find "$FAILURE_DIR" -iname "inconsistency_*" \
  | awk -F'[_.]' '{print $(NF-1), $0}' \
  | sort -n \
  | cut -d' ' -f2- \
  | head -n1)


if [[ -n "$f" ]]; then
  echo "Bug is triggered"
  # Remove last 2 path components (e.g., inconsistency/inconsistency_23.report -> failure_0)
  failure_path=$(dirname "$(dirname "$f")")
  compute_triggering_time "$failure_path"
else
  echo "bug is not triggered"
fi
