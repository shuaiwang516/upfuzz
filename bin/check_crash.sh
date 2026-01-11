#!/usr/bin/env bash
set -euo pipefail

# Usage: ./check_crash.sh [failure_dir]
# Default: failure

FAILURE_DIR="${1:-failure}"

source bin/compute_time.sh

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m'   # no color

log_info() { echo -e "${YELLOW}[INFO]${NC} $*"; }
log_ok()   { echo -e "${GREEN}[OK]${NC}   $*"; }
log_err()  { echo -e "${RED}[FAIL]${NC} $*"; }

d=$(find "$FAILURE_DIR" -type d -iname "fullstop_crash" 2>/dev/null | sort | head -n 1 || true)


if [[ -n "${d:-}" ]]; then
  # compute a dirname for d
  d=$(dirname "$d")
  # log_info "Found crash dir: $d"
  log_ok "bug is triggered!"
  compute_triggering_time "$d"
else
  log_err "bug is not triggered"
fi