#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER_DIR="${SCRIPT_DIR}/../cloudlab-runner"
DEFAULT_MACHINE_LIST="${RUNNER_DIR}/machine_list.txt"
DEFAULT_REMOTE_REPO="${DEFAULT_REMOTE_REPO:-/users/swang516/xlab/rupfuzz/upfuzz}"

MACHINE_LIST="${DEFAULT_MACHINE_LIST}"
REMOTE_REPO="${DEFAULT_REMOTE_REPO}"
PARALLEL_JOBS=6
DRY_RUN=0

usage() {
    cat <<EOF
Usage:
  $(basename "$0") [options]

Options:
  --machine-list <path>   Machine list file (default: ${DEFAULT_MACHINE_LIST})
  --remote-repo <path>    Remote repo path (default: ${DEFAULT_REMOTE_REPO})
  --parallel <N>          Host-level parallelism (default: ${PARALLEL_JOBS})
  --dry-run               Print actions only
  -h, --help              Show help

Purpose:
  Kill current fuzzing-related running state on all hosts:
  - upfuzz tmux sessions
  - fuzzing server/client Java processes
  - qemu leftovers
  - Docker containers/networks for running clusters
EOF
}

log() {
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

warn() {
    printf '[%s] WARN: %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --machine-list)
            MACHINE_LIST="$2"
            shift 2
            ;;
        --remote-repo)
            REMOTE_REPO="$2"
            shift 2
            ;;
        --parallel)
            PARALLEL_JOBS="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "Unknown argument: $1"
            ;;
    esac
done

[[ -f "${MACHINE_LIST}" ]] || die "Machine list not found: ${MACHINE_LIST}"
[[ "${PARALLEL_JOBS}" =~ ^[0-9]+$ ]] || die "--parallel must be a positive integer"
(( PARALLEL_JOBS >= 1 )) || die "--parallel must be >= 1"

declare -a MACHINE_LINES=()
while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ -z "${line// }" || "${line}" =~ ^[[:space:]]*# ]] && continue
    MACHINE_LINES+=("${line}")
done < "${MACHINE_LIST}"
[[ "${#MACHINE_LINES[@]}" -gt 0 ]] || die "No usable machine lines in ${MACHINE_LIST}"

STATUS_DIR="$(mktemp -d)"

process_machine() {
    local machine_line="$1"
    local -a ssh_cmd=()
    local host_token=""

    if [[ "${machine_line}" == ssh* ]]; then
        read -r -a ssh_cmd <<< "${machine_line}"
        host_token="${ssh_cmd[$((${#ssh_cmd[@]} - 1))]}"
    else
        host_token="${machine_line}"
        ssh_cmd=(ssh "${machine_line}")
    fi
    local host_no_user="${host_token#*@}"

    log "kill on ${host_no_user}"
    if "${ssh_cmd[@]}" "bash -s -- '$(printf "%q" "${REMOTE_REPO}")' '${DRY_RUN}'" <<'REMOTE'
set -euo pipefail
repo="$1"
dry_run="$2"

run_cmd() {
    local cmd="$1"
    if [[ "${dry_run}" == "1" ]]; then
        echo "[dry-run] ${cmd}"
    else
        bash -lc "${cmd}"
    fi
}

run_cmd 'tmux ls 2>/dev/null | cut -d: -f1 | grep "^upfuzz_" | xargs -r -n1 tmux kill-session -t || true'
run_cmd 'pkill -f "[o]rg\\.zlab\\.upfuzz\\.fuzzingengine\\.server\\.FuzzingServer" || true'
run_cmd 'pkill -f "[o]rg\\.zlab\\.upfuzz\\.fuzzingengine\\.FuzzingClient" || true'
run_cmd 'pkill -f "[u]pfuzz.*\\.jar" || true'
run_cmd 'pgrep -u "$(id -u)" -f ".*config\\.json$" | xargs -r kill -9 || true'
run_cmd 'pgrep --euid "$USER" qemu | xargs -r kill -9 || true'

if [[ -d "${repo}" && -x "${repo}/bin/clean.sh" ]]; then
    run_cmd "cd '${repo}' && bash bin/clean.sh --force >/dev/null 2>&1 || true"
fi

if command -v docker >/dev/null 2>&1; then
    run_cmd "docker ps -aq | xargs -r docker rm -f"
    run_cmd "docker network prune -f >/dev/null 2>&1 || true"
    run_cmd "docker container prune -f >/dev/null 2>&1 || true"
fi
REMOTE
    then
        cat > "${STATUS_DIR}/${host_no_user}.status" <<EOF
status=OK
host=${host_no_user}
EOF
    else
        warn "kill failed on ${host_no_user}"
        cat > "${STATUS_DIR}/${host_no_user}.status" <<EOF
status=FAIL
host=${host_no_user}
EOF
    fi
}

for machine_line in "${MACHINE_LINES[@]}"; do
    while (( $(jobs -rp | wc -l) >= PARALLEL_JOBS )); do
        sleep 0.2
    done
    process_machine "${machine_line}" &
done
wait

ok_cnt="$(awk -F'=' '/^status=OK$/ {c++} END{print c+0}' "${STATUS_DIR}"/*.status 2>/dev/null || echo 0)"
fail_cnt="$(awk -F'=' '/^status=FAIL$/ {c++} END{print c+0}' "${STATUS_DIR}"/*.status 2>/dev/null || echo 0)"

echo
log "kill summary: success=${ok_cnt} failed=${fail_cnt} dry_run=${DRY_RUN}"
if (( fail_cnt > 0 )); then
    warn "failed hosts:"
    for sf in "${STATUS_DIR}"/*.status; do
        [[ -f "${sf}" ]] || continue
        s="$(sed -n 's/^status=//p' "${sf}" | head -n1)"
        h="$(sed -n 's/^host=//p' "${sf}" | head -n1)"
        [[ "${s}" == "FAIL" ]] && warn "  ${h}"
    done
    rm -rf "${STATUS_DIR}"
    exit 1
fi

rm -rf "${STATUS_DIR}"
