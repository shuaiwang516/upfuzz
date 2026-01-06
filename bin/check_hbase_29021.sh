#!/bin/bash
f=$(find failure -iname "inconsistency_*" | sort -t '_' -k2,2n | head -n1) && [[ -n $f ]] && bin/print_time.sh "$(cut -d'/' -f1-2 <<<"$f")" || echo "bug is not triggered yet"

# Add color to the output for better visibility
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m'   # no color

if [[ -n "$f" && -f "$f" ]]; then
  echo -e "${GREEN}[OK]${NC}   bug is triggered"
  echo -e "${YELLOW}[FILE]${NC} $f"
  echo "--------------------"
  echo
  cat "$f"
else
  echo -e "${RED}[FAIL]${NC} bug is not triggered"
fi