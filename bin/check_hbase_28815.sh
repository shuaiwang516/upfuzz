#!/usr/bin/env bash
set -euo pipefail

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m'   # no color

log_info() { echo -e "${YELLOW}[INFO]${NC} $*"; }
log_ok()   { echo -e "${GREEN}[OK]${NC}   $*"; }
log_err()  { echo -e "${RED}[FAIL]${NC} $*"; }

d=$(find failure -type d -iname "fullstop_crash" 2>/dev/null | sort | head -n 1 || true)

if [[ -n "${d:-}" ]]; then
  log_info "Found crash dir: $d"

  r=$(find "$d" -type f -name "*_crash.report" 2>/dev/null | sort | head -n 1 || true)

  if [[ -n "${r:-}" ]]; then
    log_ok "Bug is triggered!"
    echo -e "${YELLOW}[REPORT]${NC} $r"
    echo "--------------------"
    cat "$r"
  else
    log_err "Bug is not triggered (found dir but no *_crash.report inside)"
    echo -e "${YELLOW}[DIR]${NC} $d"
  fi
else
  log_err "Bug is not triggered (no fullstop_crash dir found)"
fi