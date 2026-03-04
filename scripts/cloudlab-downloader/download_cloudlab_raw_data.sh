#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER_DIR="${SCRIPT_DIR}/../cloudlab-runner"

DEFAULT_MACHINE_LIST="${RUNNER_DIR}/machine_list.txt"
DEFAULT_RESULTS_ROOT="${RUNNER_DIR}/results"
DEFAULT_REMOTE_REPO="${DEFAULT_REMOTE_REPO:-/users/swang516/xlab/rupfuzz/upfuzz}"

MACHINE_LIST="${DEFAULT_MACHINE_LIST}"
RESULTS_ROOT="${DEFAULT_RESULTS_ROOT}"
REMOTE_REPO="${DEFAULT_REMOTE_REPO}"
TARGET_ROOT=""
DISPATCH_TSV=""
RUN_NAME_OVERRIDE=""
DRY_RUN=0
USE_DELETE=1
PARALLEL_JOBS=6

usage() {
    cat <<EOF
Usage:
  $(basename "$0") --target-root <path> [options]

Options:
  --target-root <path>      Target raw_data root (e.g. /home/shuai/xlab/rupfuzz/cloudlab-results/mar3/raw_data)
  --machine-list <path>     Machine list file (default: ${DEFAULT_MACHINE_LIST})
  --results-root <path>     Local cloudlab-runner results root (default: ${DEFAULT_RESULTS_ROOT})
  --dispatch-tsv <path>     Explicit dispatch.tsv for host->run_name mapping
  --run-name <name>         Force one run_name for all machines (skip dispatch/auto-detect)
  --remote-repo <path>      Remote repo root on CloudLab machine (default: ${DEFAULT_REMOTE_REPO})
  --dry-run                 Print what would be copied, no data transfer
  --no-delete               Do not delete stale local files when syncing
  --parallel <N>            Host-level parallelism (default: ${PARALLEL_JOBS})
  -h, --help                Show this help

Downloaded structure per host:
  <target-root>/<host>/
    runner_result/
    cloudlab_result/
    logs/
    failure/

Defaults:
  - Reads machine list from scripts/cloudlab-runner/machine_list.txt
  - Uses latest dispatch.tsv under scripts/cloudlab-runner/results if present
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

latest_dispatch_tsv() {
    local root="$1"
    local p=""
    p="$(ls -1dt "${root}"/distributed_cloudlab_cont_*/dispatch.tsv 2>/dev/null | head -n1 || true)"
    if [[ -n "${p}" ]]; then
        echo "${p}"
        return 0
    fi
    p="$(ls -1dt "${root}"/distributed_*/dispatch.tsv 2>/dev/null | head -n1 || true)"
    if [[ -n "${p}" ]]; then
        echo "${p}"
        return 0
    fi
    return 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --target-root)
            TARGET_ROOT="$2"
            shift 2
            ;;
        --machine-list)
            MACHINE_LIST="$2"
            shift 2
            ;;
        --results-root)
            RESULTS_ROOT="$2"
            shift 2
            ;;
        --dispatch-tsv)
            DISPATCH_TSV="$2"
            shift 2
            ;;
        --run-name)
            RUN_NAME_OVERRIDE="$2"
            shift 2
            ;;
        --remote-repo)
            REMOTE_REPO="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=1
            shift
            ;;
        --no-delete)
            USE_DELETE=0
            shift
            ;;
        --parallel)
            PARALLEL_JOBS="$2"
            shift 2
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

[[ -n "${TARGET_ROOT}" ]] || die "--target-root is required"
[[ -f "${MACHINE_LIST}" ]] || die "Machine list not found: ${MACHINE_LIST}"
[[ "${PARALLEL_JOBS}" =~ ^[0-9]+$ ]] || die "--parallel must be a positive integer"
(( PARALLEL_JOBS >= 1 )) || die "--parallel must be >= 1"
mkdir -p "${TARGET_ROOT}"

declare -A HOST_TO_RUN
if [[ -z "${RUN_NAME_OVERRIDE}" ]]; then
    if [[ -z "${DISPATCH_TSV}" ]]; then
        DISPATCH_TSV="$(latest_dispatch_tsv "${RESULTS_ROOT}" || true)"
    fi
    if [[ -n "${DISPATCH_TSV}" ]]; then
        [[ -f "${DISPATCH_TSV}" ]] || die "Dispatch TSV not found: ${DISPATCH_TSV}"
        while IFS=$'\t' read -r job_id machine_line run_name status message; do
            [[ -z "${job_id}" || "${job_id}" == "job_id" ]] && continue
            host_token="$(echo "${machine_line}" | awk '{print $NF}')"
            host_no_user="${host_token#*@}"
            HOST_TO_RUN["${host_token}"]="${run_name}"
            HOST_TO_RUN["${host_no_user}"]="${run_name}"
        done < "${DISPATCH_TSV}"
    fi
fi

declare -a MACHINE_LINES=()
while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ -z "${line// }" || "${line}" =~ ^[[:space:]]*# ]] && continue
    MACHINE_LINES+=("${line}")
done < "${MACHINE_LIST}"
[[ "${#MACHINE_LINES[@]}" -gt 0 ]] || die "No usable machine lines in ${MACHINE_LIST}"

resolve_run_name_remote() {
    local ssh_cmd_str="$1"
    local repo="$2"
    bash -lc "${ssh_cmd_str} \"bash -s -- '$(printf '%q' "${repo}")'\" <<'REMOTE'
set -euo pipefail
repo=\"$1\"
latest=\"$(ls -1dt \"${repo}/scripts/runner/results\"/* 2>/dev/null | head -n1 || true)\"
if [[ -n \"${latest}\" ]]; then
    basename \"${latest}\"
fi
REMOTE"
}

remote_dir_exists() {
    local ssh_cmd_str="$1"
    local path="$2"
    bash -lc "${ssh_cmd_str} \"test -d '$(printf '%q' "${path}")'\""
}

sync_dir() {
    local host_token="$1"
    local remote_path="$2"
    local local_path="$3"
    local ssh_remote_opts="-o StrictHostKeyChecking=no"
    local -a rsync_cmd=(rsync -az)

    [[ "${USE_DELETE}" -eq 1 ]] && rsync_cmd+=(--delete)
    [[ "${DRY_RUN}" -eq 1 ]] && rsync_cmd+=(--dry-run)
    rsync_cmd+=(-e "ssh ${ssh_remote_opts}" "${host_token}:${remote_path}/" "${local_path}/")

    mkdir -p "${local_path}"
    "${rsync_cmd[@]}"
}

STATUS_DIR="$(mktemp -d)"

process_machine() {
    local machine_line="$1"
    ssh_cmd=()
    host_token=""
    if [[ "${machine_line}" == ssh* ]]; then
        read -r -a ssh_cmd <<< "${machine_line}"
        host_token="${ssh_cmd[$((${#ssh_cmd[@]} - 1))]}"
    else
        host_token="${machine_line}"
        ssh_cmd=(ssh "${machine_line}")
    fi

    host_no_user="${host_token#*@}"
    ssh_cmd_str="$(printf '%q ' "${ssh_cmd[@]}")"
    ssh_cmd_str="${ssh_cmd_str% }"

    run_name="${RUN_NAME_OVERRIDE}"
    if [[ -z "${run_name}" ]]; then
        run_name="${HOST_TO_RUN[${host_token}]:-${HOST_TO_RUN[${host_no_user}]:-}}"
    fi
    if [[ -z "${run_name}" ]]; then
        run_name="$(resolve_run_name_remote "${ssh_cmd_str}" "${REMOTE_REPO}" || true)"
    fi

    if [[ -z "${run_name}" ]]; then
        warn "skip ${host_no_user}: cannot resolve run_name"
        cat > "${STATUS_DIR}/${host_no_user}.status" <<EOF
status=FAIL
host=${host_no_user}
run_name=
EOF
        return 0
    fi

    log "sync ${host_no_user} (run_name=${run_name})"

    host_target="${TARGET_ROOT}/${host_no_user}"
    mkdir -p "${host_target}"

    remote_runner="${REMOTE_REPO}/scripts/runner/results/${run_name}"
    remote_cloudlab="${REMOTE_REPO}/scripts/cloudlab-runner/results/${run_name}"
    remote_logs="${REMOTE_REPO}/logs"
    remote_failure="${REMOTE_REPO}/failure"

    ok=1
    for pair in \
        "runner_result|${remote_runner}" \
        "cloudlab_result|${remote_cloudlab}" \
        "logs|${remote_logs}" \
        "failure|${remote_failure}"; do
        name="${pair%%|*}"
        remote_dir="${pair#*|}"
        local_dir="${host_target}/${name}"

        if ! remote_dir_exists "${ssh_cmd_str}" "${remote_dir}"; then
            warn "${host_no_user}: remote dir missing: ${remote_dir}"
            mkdir -p "${local_dir}"
            continue
        fi

        if ! sync_dir "${host_token}" "${remote_dir}" "${local_dir}"; then
            warn "${host_no_user}: rsync failed for ${name}"
            ok=0
        fi
    done

    meta_file="${host_target}/download_meta.env"
    cat > "${meta_file}" <<EOF
HOST=${host_no_user}
RUN_NAME=${run_name}
REMOTE_REPO=${REMOTE_REPO}
DOWNLOADED_AT=$(date '+%Y-%m-%d %H:%M:%S')
EOF

    if [[ "${ok}" -eq 1 ]]; then
        cat > "${STATUS_DIR}/${host_no_user}.status" <<EOF
status=OK
host=${host_no_user}
run_name=${run_name}
EOF
    else
        cat > "${STATUS_DIR}/${host_no_user}.status" <<EOF
status=FAIL
host=${host_no_user}
run_name=${run_name}
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

echo
host_success="$(awk -F'=' '/^status=OK$/ {c++} END{print c+0}' "${STATUS_DIR}"/*.status 2>/dev/null || echo 0)"
host_failed="$(awk -F'=' '/^status=FAIL$/ {c++} END{print c+0}' "${STATUS_DIR}"/*.status 2>/dev/null || echo 0)"

if (( host_failed > 0 )); then
    warn "failed hosts:"
    for sf in "${STATUS_DIR}"/*.status; do
        [[ -f "${sf}" ]] || continue
        s="$(sed -n 's/^status=//p' "${sf}" | head -n1)"
        h="$(sed -n 's/^host=//p' "${sf}" | head -n1)"
        r="$(sed -n 's/^run_name=//p' "${sf}" | head -n1)"
        if [[ "${s}" == "FAIL" ]]; then
            warn "  host=${h} run_name=${r}"
        fi
    done
fi

log "done: success=${host_success} failed=${host_failed} target_root=${TARGET_ROOT} dry_run=${DRY_RUN} parallel=${PARALLEL_JOBS}"
if [[ -n "${DISPATCH_TSV}" ]]; then
    log "dispatch_tsv=${DISPATCH_TSV}"
fi

rm -rf "${STATUS_DIR}"
