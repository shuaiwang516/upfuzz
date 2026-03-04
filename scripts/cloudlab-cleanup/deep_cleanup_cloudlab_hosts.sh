#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER_DIR="${SCRIPT_DIR}/../cloudlab-runner"
DEFAULT_MACHINE_LIST="${RUNNER_DIR}/machine_list.txt"
DEFAULT_REMOTE_REPO="${DEFAULT_REMOTE_REPO:-/users/swang516/xlab/rupfuzz/upfuzz}"
KILL_SCRIPT="${SCRIPT_DIR}/kill_cloudlab_running.sh"

MACHINE_LIST="${DEFAULT_MACHINE_LIST}"
REMOTE_REPO="${DEFAULT_REMOTE_REPO}"
PARALLEL_JOBS=6
DRY_RUN=0
REMOVE_IMAGES=1

usage() {
    cat <<EOF
Usage:
  $(basename "$0") [options]

Options:
  --machine-list <path>   Machine list file (default: ${DEFAULT_MACHINE_LIST})
  --remote-repo <path>    Remote repo path (default: ${DEFAULT_REMOTE_REPO})
  --parallel <N>          Host-level parallelism (default: ${PARALLEL_JOBS})
  --keep-images           Do not remove upfuzz_* docker images
  --dry-run               Print actions only
  -h, --help              Show help

Purpose:
  Deep cleanup for CloudLab hosts:
  1) Call kill_cloudlab_running.sh to stop all running fuzzing/docker state
  2) Remove fuzzing-generated artifacts so hosts look "never fuzzed"
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
        --keep-images)
            REMOVE_IMAGES=0
            shift
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
[[ -f "${KILL_SCRIPT}" ]] || die "Missing kill script: ${KILL_SCRIPT}"
[[ "${PARALLEL_JOBS}" =~ ^[0-9]+$ ]] || die "--parallel must be a positive integer"
(( PARALLEL_JOBS >= 1 )) || die "--parallel must be >= 1"

declare -a MACHINE_LINES=()
while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ -z "${line// }" || "${line}" =~ ^[[:space:]]*# ]] && continue
    MACHINE_LINES+=("${line}")
done < "${MACHINE_LIST}"
[[ "${#MACHINE_LINES[@]}" -gt 0 ]] || die "No usable machine lines in ${MACHINE_LIST}"

log "Step 1/2: kill running fuzzing/docker state on all machines"
KILL_CMD=(bash "${KILL_SCRIPT}" --machine-list "${MACHINE_LIST}" --remote-repo "${REMOTE_REPO}" --parallel "${PARALLEL_JOBS}")
if (( DRY_RUN == 1 )); then
    KILL_CMD+=(--dry-run)
fi
"${KILL_CMD[@]}"

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

    log "deep cleanup on ${host_no_user}"
    if "${ssh_cmd[@]}" "bash -s -- '$(printf "%q" "${REMOTE_REPO}")' '${DRY_RUN}' '${REMOVE_IMAGES}'" <<'REMOTE'
set -euo pipefail
repo="$1"
dry_run="$2"
remove_images="$3"

run_cmd() {
    local cmd="$1"
    if [[ "${dry_run}" == "1" ]]; then
        echo "[dry-run] ${cmd}"
    else
        bash -lc "${cmd}"
    fi
}

has_sudo=0
if command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; then
    has_sudo=1
fi

remove_path() {
    local path="$1"
    if [[ "${dry_run}" == "1" ]]; then
        echo "[dry-run] rm -rf -- '${path}'"
        return 0
    fi

    if rm -rf -- "${path}" 2>/dev/null; then
        return 0
    fi

    if (( has_sudo == 1 )); then
        sudo -n rm -rf -- "${path}" 2>/dev/null
        return 0
    fi
    return 1
}

ensure_dir_owned() {
    local path="$1"
    if [[ "${dry_run}" == "1" ]]; then
        echo "[dry-run] mkdir -p -- '${path}'"
        return 0
    fi

    if ! mkdir -p -- "${path}" 2>/dev/null; then
        if (( has_sudo == 1 )); then
            sudo -n mkdir -p -- "${path}" 2>/dev/null
        else
            return 1
        fi
    fi

    if ! chown -R "$(id -u):$(id -g)" "${path}" 2>/dev/null; then
        if (( has_sudo == 1 )); then
            sudo -n chown -R "$(id -u):$(id -g)" "${path}" 2>/dev/null || true
        fi
    fi
}

if [[ ! -d "${repo}" ]]; then
    echo "repo not found: ${repo}" >&2
    exit 2
fi

for d in \
    "${repo}/scripts/runner/results" \
    "${repo}/scripts/cloudlab-runner/results" \
    "${repo}/logs" \
    "${repo}/failure" \
    "${repo}/fuzzing_storage" \
    "${repo}/corpus" \
    "${repo}/graph"; do
    remove_path "${d}"
    ensure_dir_owned "${d}"
done

remove_path "${repo}/config.json"
remove_path "${repo}/zlab-jacoco.exec"

if [[ "${dry_run}" == "1" ]]; then
    echo "[dry-run] find '${repo}' -maxdepth 1 -type f -name 'snapshot.*' -delete"
else
    if ! find "${repo}" -maxdepth 1 -type f -name 'snapshot.*' -delete 2>/dev/null; then
        if (( has_sudo == 1 )); then
            sudo -n find "${repo}" -maxdepth 1 -type f -name 'snapshot.*' -delete 2>/dev/null
        else
            echo "failed to delete snapshot.* under ${repo}" >&2
            exit 3
        fi
    fi
fi

# Clean transient temp roots commonly used by HDFS/HBase upfuzz runs.
if [[ "${dry_run}" == "1" ]]; then
    echo "[dry-run] rm -rf -- /tmp/upfuzz"
else
    if [[ -d /tmp/upfuzz ]]; then
        if ! rm -rf -- /tmp/upfuzz 2>/dev/null; then
            if (( has_sudo == 1 )); then
                sudo -n rm -rf -- /tmp/upfuzz 2>/dev/null
            else
                echo "failed to delete /tmp/upfuzz (no sudo)" >&2
                exit 3
            fi
        fi
    fi
fi

if command -v docker >/dev/null 2>&1; then
    if [[ "${remove_images}" == "1" ]]; then
        run_cmd "docker images --format '{{.Repository}}:{{.Tag}} {{.Repository}}' | awk '\$2 ~ /^upfuzz_/ {print \$1}' | xargs -r docker rmi -f"
    fi
    run_cmd "docker image prune -f >/dev/null 2>&1 || true"
    run_cmd "docker volume prune -f >/dev/null 2>&1 || true"
fi
REMOTE
    then
        cat > "${STATUS_DIR}/${host_no_user}.status" <<EOF
status=OK
host=${host_no_user}
EOF
    else
        warn "deep cleanup failed on ${host_no_user}"
        cat > "${STATUS_DIR}/${host_no_user}.status" <<EOF
status=FAIL
host=${host_no_user}
EOF
    fi
}

log "Step 2/2: remove fuzzing artifacts and storage on all machines"
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
log "deep cleanup summary: success=${ok_cnt} failed=${fail_cnt} dry_run=${DRY_RUN} remove_images=${REMOVE_IMAGES}"
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
