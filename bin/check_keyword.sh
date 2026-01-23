#!/bin/bash
# Usage: ./check_keyword.sh [failure_dir] [keyword]
# Default: failure

FAILURE_DIR="${1:-failure}"

if [[ -z "$2" ]]; then
  echo "Error: keyword must be specified as the second argument." >&2
  exit 1
fi
KEYWORD="$2"
source bin/compute_time.sh

f=$(fgrep -rl "$KEYWORD" "$FAILURE_DIR" \
| while IFS= read -r f; do
    fid="$(basename "$(dirname "$(dirname "$f")")")"   # => failure_204
    num="${fid#failure_}"                              # => 204
    printf "%s\t%s\n" "$num" "$f"
  done \
| sort -n -k1,1 \
| cut -f2- \
| head -n1)

if [[ -n "$f" && -f "$f" ]]; then
  echo "bug is triggered"
else
  echo "bug is not triggered"
  exit 1
fi

failure_path=$(dirname "$(dirname "$f")")
compute_triggering_time "$failure_path"
