#!/bin/bash

# Usage: ./check_hbase_29021.sh [failure_dir]
# Default: failure

FAILURE_DIR="${1:-failure}"

# Add color to the output for better visibility
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m'   # no color


# Get the first inconsistency file and extract path up to failure_X
f=$(find "$FAILURE_DIR" -iname "inconsistency_*" | sort -t '_' -k2,2n | head -n1)
if [[ -n "$f" ]]; then
  echo -e "${GREEN}[OK]${NC}   Bug is triggered"
  echo -e "${YELLOW}[FILE]${NC} $f"
  # Remove last 2 path components (e.g., inconsistency/inconsistency_23.report -> failure_0)
  failure_path=$(dirname "$(dirname "$f")")
  bin/print_time.sh "$failure_path"
else
  echo -e "${RED}[FAIL]${NC} bug is not triggered"
fi