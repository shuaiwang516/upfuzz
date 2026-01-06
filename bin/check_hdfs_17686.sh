#!/bin/bash
source bin/compute_time.sh

# Add color to the output for better visibility
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m'   # no color

DIR_NAME=$(find failure -type f -name "incons*" | sort -t_ -k2,2n | head -n1 | awk -F'/' '{print $1 "/" $2}')

if [[ -z "$DIR_NAME" ]]; then
  echo -e "${RED}[FAIL]${NC} bug is not triggered"
  exit 1
fi

echo -e "${GREEN}[OK]${NC}   bug is triggered"
echo -e "${YELLOW}[DIR]${NC}  $DIR_NAME"
echo "--------------------"
echo
compute_triggering_time $DIR_NAME
