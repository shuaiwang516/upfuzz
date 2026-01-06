#!/bin/bash
f=$(grep -rl 'java.lang.RuntimeException: java.lang.NullPointerException' failure | sort -t '_' -k2,2n | head -n1)

# Add color to the output for better visibility
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m'   # no color

if [[ -n "$f" ]]; then
  echo -e "${GREEN}[OK]${NC}   bug is triggered"
  echo -e "${YELLOW}[FILE]${NC} $f"
  echo "--------------------"
  echo
  cat "$f"
else
  echo -e "${RED}[FAIL]${NC} bug is not triggered"
fi
