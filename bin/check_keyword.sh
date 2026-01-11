#!/bin/bash
# Usage: ./check_keyword.sh [failure_dir] [keyword]
# Default: failure

FAILURE_DIR="${1:-failure}"

# f=$(grep -rl "CommitLogReplayException" "$FAILURE_DIR" | sort -t '_' -k2,2n | head -n 1)

KEYWORD="${2:-CommitLogReplayException}"
source bin/compute_time.sh

# f=$(find "$FAILURE_DIR" -iname "$KEYWORD" \
#   | awk -F'[_.]' '{print $(NF-1), $0}' \
#   | sort -n \
#   | cut -d' ' -f2- \
#   | head -n1)

f=$(fgrep -rl "$KEYWORD" "$FAILURE_DIR" \
| while IFS= read -r f; do
    fid="$(basename "$(dirname "$(dirname "$f")")")"   # => failure_204
    num="${fid#failure_}"                              # => 204
    printf "%s\t%s\n" "$num" "$f"
  done \
| sort -n -k1,1 \
| cut -f2- \
| head -n1)

failure_path=$(dirname "$(dirname "$f")")
compute_triggering_time "$failure_path"

# Add color to the output for better visibility
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m'   # no color

if [[ -n "$f" && -f "$f" ]]; then
  echo -e "${GREEN}[OK]${NC}   bug is triggered"
  echo -e "${YELLOW}[FILE]${NC} $f"
  # echo "--------------------"
  # echo
  # cat "$f"
else
  echo -e "${RED}[FAIL]${NC} bug is not triggered"
fi
