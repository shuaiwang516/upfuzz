#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BASE_SCRIPT="${SCRIPT_DIR}/run_cloudlab_job.sh"

DEFAULT_ROUNDS="${DEFAULT_ROUNDS:-2147483647}"
DEFAULT_TIMEOUT_SEC="${DEFAULT_TIMEOUT_SEC:-2147483647}"
DEFAULT_MACHINE_LIST="${DEFAULT_MACHINE_LIST:-${SCRIPT_DIR}/machine_list.txt}"
DEFAULT_REMOTE_REPO="${DEFAULT_REMOTE_REPO:-/users/swang516/xlab/rupfuzz/upfuzz}"

USE_TMUX=true
DETACH=false
RUN_NAME=""
RUN_PREFIX=""
MACHINE_LIST="${DEFAULT_MACHINE_LIST}"
REMOTE_REPO="${DEFAULT_REMOTE_REPO}"
TMUX_SESSION=""
HAS_ROUNDS=false
HAS_TIMEOUT=false
HAS_LIST_JOBS=false
USER_JOB_SELECTOR=false
DISTRIBUTE=false
DRY_RUN=false

PASSTHRU_ARGS=()

usage() {
    cat <<'USAGE'
Usage:
  run_cloudlab_fuzz_job.sh [options...]
  run_cloudlab_fuzz_job.sh --distribute [options...]

Description:
  Continuous fuzzing launcher for CloudLab jobs.
  It wraps run_cloudlab_job.sh but defaults to very large stop limits:
    --rounds 2147483647
    --timeout-sec 2147483647
  so fuzzing does not stop after demo-scale 1-2 rounds.

Options:
  --no-tmux                  Disable tmux and use legacy foreground/detach behavior.
  --tmux-session <name>      Explicit tmux session name (default: upfuzz_<run_name>).
  --detach                    Run in background and return immediately.
  --distribute                Read machine list and dispatch one job per machine.
  --machine-list <path>       Machine list file (default: scripts/cloudlab-runner/machine_list.txt).
  --remote-repo <path>        Upfuzz repo path on remote machines.
  --run-prefix <prefix>       Prefix for distributed run names.
  --dry-run                   Print distribution commands without executing.
  --rounds <N>                Override rounds limit (default: 2147483647).
  --timeout-sec <N>           Override timeout (default: 2147483647 sec).
  --run-name <name>           Explicit run name (single mode) or default run-prefix (distribute mode).
  -h, --help                  Show this help.

All other options are passed through to:
  scripts/cloudlab-runner/run_cloudlab_job.sh

Examples:
  scripts/cloudlab-runner/run_cloudlab_fuzz_job.sh --job-id 1
  scripts/cloudlab-runner/run_cloudlab_fuzz_job.sh --job-id 2 --detach --run-name cloudlab_fuzz_job2
  scripts/cloudlab-runner/run_cloudlab_fuzz_job.sh --job-id 5 --skip-docker-build --skip-build
  scripts/cloudlab-runner/run_cloudlab_fuzz_job.sh --distribute --skip-docker-build --skip-build
  scripts/cloudlab-runner/run_cloudlab_fuzz_job.sh --distribute --machine-list scripts/cloudlab-runner/machine_list.txt

Notes:
  - Default mode launches a detached tmux session and exits.
  - Use `tmux ls` and `tmux attach -t <session>` to inspect a run.
  - In --no-tmux mode, foreground/detach behavior is the same as legacy behavior.
  - Distribute mode dispatches jobs in list-jobs order to machine-list order.
USAGE
}

log() {
    printf '[%s] %s\n' "$(date '+%F %T')" "$*"
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

sanitize_name() {
    echo "$1" | sed 's/[^a-zA-Z0-9._-]/_/g'
}

collect_job_ids() {
    local ids=()
    mapfile -t ids < <("${BASE_SCRIPT}" --list-jobs | awk '$1 ~ /^[0-9]+$/ {print $1}')
    if [[ "${#ids[@]}" -eq 0 ]]; then
        die "Failed to parse job ids from: ${BASE_SCRIPT} --list-jobs"
    fi
    printf '%s\n' "${ids[@]}"
}

collect_machine_lines() {
    local file="$1"
    [[ -f "${file}" ]] || die "Machine list file not found: ${file}"
    local machines=()
    mapfile -t machines < <(sed -E '/^[[:space:]]*($|#)/d' "${file}")
    if [[ "${#machines[@]}" -eq 0 ]]; then
        die "No usable machine lines in ${file}"
    fi
    printf '%s\n' "${machines[@]}"
}

extract_host_tag() {
    local machine_line="$1"
    local host
    if [[ "${machine_line}" == ssh* ]]; then
        local parts=()
        read -r -a parts <<< "${machine_line}"
        host="${parts[$((${#parts[@]} - 1))]}"
    else
        host="${machine_line}"
    fi
    host="${host#*@}"
    host="${host%%:*}"
    sanitize_name "${host}"
}

run_distributed() {
    command -v ssh >/dev/null 2>&1 || die "Missing command: ssh"

    if [[ "${USER_JOB_SELECTOR}" == true ]]; then
        die "--distribute cannot be combined with --job-id/--system/--original/--upgraded"
    fi

    local job_ids=()
    local machine_lines=()
    mapfile -t job_ids < <(collect_job_ids)
    mapfile -t machine_lines < <(collect_machine_lines "${MACHINE_LIST}")

    local job_count="${#job_ids[@]}"
    local machine_count="${#machine_lines[@]}"

    # Required gate for flexible future scaling:
    # distribute when jobs <= machines; otherwise fail.
    if (( job_count > machine_count )); then
        die "job_num(${job_count}) > machine_num(${machine_count}); cannot distribute all jobs"
    fi

    if (( job_count < machine_count )); then
        log "job_num(${job_count}) < machine_num(${machine_count}); using first ${job_count} machines"
    else
        log "job_num(${job_count}) == machine_num(${machine_count}); one job per machine"
    fi

    local prefix
    if [[ -n "${RUN_PREFIX}" ]]; then
        prefix="${RUN_PREFIX}"
    elif [[ -n "${RUN_NAME}" ]]; then
        prefix="${RUN_NAME}"
    else
        prefix="cloudlab_dist_$(date '+%Y%m%d_%H%M%S')"
    fi
    prefix="$(sanitize_name "${prefix}")"

    local dist_dir="${SCRIPT_DIR}/results/distributed_${prefix}"
    mkdir -p "${dist_dir}"
    local dispatch_file="${dist_dir}/dispatch.tsv"
    local dispatch_log="${dist_dir}/dispatch.log"

    {
        echo "job_id	machine	run_name	status	message"
    } > "${dispatch_file}"
    : > "${dispatch_log}"

    local remote_args=("${PASSTHRU_ARGS[@]}")
    if [[ "${HAS_ROUNDS}" == false ]]; then
        remote_args+=(--rounds "${DEFAULT_ROUNDS}")
    fi
    if [[ "${HAS_TIMEOUT}" == false ]]; then
        remote_args+=(--timeout-sec "${DEFAULT_TIMEOUT_SEC}")
    fi

    local quoted_remote_args=""
    local arg
    for arg in "${remote_args[@]}"; do
        quoted_remote_args+=" $(printf '%q' "${arg}")"
    done

    local failures=0
    local idx
    for ((idx = 0; idx < job_count; idx++)); do
        local job_id="${job_ids[$idx]}"
        local machine_line="${machine_lines[$idx]}"
        local host_tag
        host_tag="$(extract_host_tag "${machine_line}")"
        local run_name="${prefix}_job${job_id}_${host_tag}"
        run_name="$(sanitize_name "${run_name}")"

        local ssh_cmd=()
        if [[ "${machine_line}" == ssh* ]]; then
            read -r -a ssh_cmd <<< "${machine_line}"
        else
            ssh_cmd=(ssh "${machine_line}")
        fi

        local remote_cmd
        remote_cmd="cd $(printf '%q' "${REMOTE_REPO}") && scripts/cloudlab-runner/run_cloudlab_fuzz_job.sh --job-id ${job_id} --run-name $(printf '%q' "${run_name}") --detach${quoted_remote_args}"

        log "Dispatch job ${job_id} -> ${machine_line} (run=${run_name})" | tee -a "${dispatch_log}"

        if [[ "${DRY_RUN}" == true ]]; then
            {
                printf "DRYRUN\t%s\t%s\t%s\n" "${machine_line}" "${job_id}" "${remote_cmd}"
            } >> "${dispatch_log}"
            printf "%s\t%s\t%s\t%s\t%s\n" "${job_id}" "${machine_line}" "${run_name}" "DRYRUN" "not executed" >> "${dispatch_file}"
            continue
        fi

        local per_log="${dist_dir}/${run_name}.dispatch.log"
        if "${ssh_cmd[@]}" "${remote_cmd}" > "${per_log}" 2>&1; then
            printf "%s\t%s\t%s\t%s\t%s\n" "${job_id}" "${machine_line}" "${run_name}" "OK" "launched" >> "${dispatch_file}"
        else
            failures=$((failures + 1))
            printf "%s\t%s\t%s\t%s\t%s\n" "${job_id}" "${machine_line}" "${run_name}" "FAILED" "ssh/launch failed" >> "${dispatch_file}"
            log "Dispatch failed for job ${job_id} on ${machine_line}; see ${per_log}" | tee -a "${dispatch_log}"
        fi
    done

    log "Dispatch mapping saved: ${dispatch_file}" | tee -a "${dispatch_log}"
    log "Dispatch logs dir: ${dist_dir}" | tee -a "${dispatch_log}"
    if (( failures > 0 )); then
        die "Distribution finished with ${failures} failure(s)"
    fi
}

run_in_tmux() {
    command -v tmux >/dev/null 2>&1 || die "Missing command: tmux"

    if [[ -z "${RUN_NAME}" ]]; then
        RUN_NAME="cloudlab_fuzz_$(date '+%Y%m%d_%H%M%S')"
    fi
    if [[ "${HAS_ROUNDS}" == false ]]; then
        PASSTHRU_ARGS+=(--rounds "${DEFAULT_ROUNDS}")
    fi
    if [[ "${HAS_TIMEOUT}" == false ]]; then
        PASSTHRU_ARGS+=(--timeout-sec "${DEFAULT_TIMEOUT_SEC}")
    fi
    PASSTHRU_ARGS+=(--run-name "${RUN_NAME}")

    local session_name
    if [[ -n "${TMUX_SESSION}" ]]; then
        session_name="$(sanitize_name "${TMUX_SESSION}")"
    else
        session_name="$(sanitize_name "upfuzz_${RUN_NAME}")"
    fi
    [[ -n "${session_name}" ]] || die "Invalid tmux session name"

    if tmux has-session -t "${session_name}" 2>/dev/null; then
        die "tmux session already exists: ${session_name}"
    fi

    local state_dir="${SCRIPT_DIR}/results/${RUN_NAME}"
    local tmux_log="${state_dir}/tmux_session.log"
    mkdir -p "${state_dir}"

    local cmd=("${BASE_SCRIPT}" "${PASSTHRU_ARGS[@]}")
    local cmd_str=""
    local arg
    for arg in "${cmd[@]}"; do
        cmd_str+=" $(printf '%q' "${arg}")"
    done

    local tmux_cmd
    tmux_cmd="cd $(printf '%q' "${ROOT_DIR}") && {${cmd_str}; } 2>&1 | tee -a $(printf '%q' "${tmux_log}")"
    tmux new-session -d -s "${session_name}" "${tmux_cmd}"

    echo "${session_name}" > "${state_dir}/tmux_session.txt"
    echo "${tmux_log}" > "${state_dir}/tmux_log_path.txt"

    log "Started tmux session: ${session_name}"
    log "Run name: ${RUN_NAME}"
    log "tmux log: ${tmux_log}"
    log "Attach: tmux attach -t ${session_name}"
}

if [[ ! -x "${BASE_SCRIPT}" ]]; then
    die "Missing or non-executable base script: ${BASE_SCRIPT}"
fi

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-tmux)
            USE_TMUX=false
            shift 1
            ;;
        --tmux-session)
            TMUX_SESSION="$2"
            shift 2
            ;;
        --detach)
            DETACH=true
            shift 1
            ;;
        --distribute)
            DISTRIBUTE=true
            shift 1
            ;;
        --machine-list)
            MACHINE_LIST="$2"
            shift 2
            ;;
        --remote-repo)
            REMOTE_REPO="$2"
            shift 2
            ;;
        --run-prefix)
            RUN_PREFIX="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift 1
            ;;
        --job-id|--system|--original|--upgraded)
            USER_JOB_SELECTOR=true
            PASSTHRU_ARGS+=("$1" "$2")
            shift 2
            ;;
        --rounds)
            HAS_ROUNDS=true
            PASSTHRU_ARGS+=("$1" "$2")
            shift 2
            ;;
        --timeout-sec)
            HAS_TIMEOUT=true
            PASSTHRU_ARGS+=("$1" "$2")
            shift 2
            ;;
        --run-name)
            RUN_NAME="$2"
            shift 2
            ;;
        --list-jobs)
            HAS_LIST_JOBS=true
            shift 1
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            PASSTHRU_ARGS+=("$1")
            shift 1
            ;;
    esac
done

if [[ "${HAS_LIST_JOBS}" == true ]]; then
    exec "${BASE_SCRIPT}" --list-jobs
fi

if [[ "${DISTRIBUTE}" == true ]]; then
    run_distributed
    exit 0
fi

if [[ "${USE_TMUX}" == true ]]; then
    if [[ "${DETACH}" == true ]]; then
        log "--detach is redundant in tmux mode; continuing with tmux launch"
    fi
    run_in_tmux
    exit 0
fi

if [[ "${HAS_ROUNDS}" == false ]]; then
    PASSTHRU_ARGS+=(--rounds "${DEFAULT_ROUNDS}")
fi
if [[ "${HAS_TIMEOUT}" == false ]]; then
    PASSTHRU_ARGS+=(--timeout-sec "${DEFAULT_TIMEOUT_SEC}")
fi
if [[ -n "${RUN_NAME}" ]]; then
    PASSTHRU_ARGS+=(--run-name "${RUN_NAME}")
fi

if [[ "${DETACH}" == true ]]; then
    if [[ -z "${RUN_NAME}" ]]; then
        RUN_NAME="cloudlab_fuzz_$(date '+%Y%m%d_%H%M%S')"
        PASSTHRU_ARGS+=(--run-name "${RUN_NAME}")
    fi

    STATE_DIR="${SCRIPT_DIR}/results/${RUN_NAME}"
    PID_FILE="${STATE_DIR}/fuzz_pid.txt"
    WRAPPER_LOG="${STATE_DIR}/fuzz_wrapper.log"
    mkdir -p "${STATE_DIR}"

    CMD=("${BASE_SCRIPT}" "${PASSTHRU_ARGS[@]}")

    log "Starting detached continuous fuzzing: ${CMD[*]}"
    (
        cd "${ROOT_DIR}"
        nohup "${CMD[@]}" > "${WRAPPER_LOG}" 2>&1 &
        echo "$!" > "${PID_FILE}"
    )

    log "Detached PID: $(cat "${PID_FILE}")"
    log "Wrapper log: ${WRAPPER_LOG}"
    log "Run name: ${RUN_NAME}"
    exit 0
fi

CMD=("${BASE_SCRIPT}" "${PASSTHRU_ARGS[@]}")
log "Starting foreground continuous fuzzing: ${CMD[*]}"
cd "${ROOT_DIR}"
exec "${CMD[@]}"
