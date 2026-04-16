#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RUNNER_SCRIPT="${ROOT_DIR}/scripts/runner/run_rolling_fuzzing.sh"
DOCKER_BUILD_SCRIPT="${ROOT_DIR}/scripts/docker/build_rolling_image_pair.sh"
RESULTS_ROOT="${SCRIPT_DIR}/results"
mkdir -p "${RESULTS_ROOT}"

JOB_ID=""
SYSTEM=""
ORIGINAL_VERSION=""
UPGRADED_VERSION=""
RUN_NAME=""
ROUNDS=1
TIMEOUT_SEC=3600
CLIENTS=1
TESTING_MODE=5
NODE_NUM=""
DIFF_LANE_TIMEOUT_SEC=1200
HBASE_DAEMON_RETRY_TIMES=""
SKIP_BUILD=false
SKIP_DOCKER_BUILD=false

usage() {
    cat <<'USAGE'
Usage:
  run_cloudlab_job.sh [options]

Options:
  --job-id <1..6>                    Predefined job mapping for 6-machine split
  --system <cassandra|hbase|hdfs>    Manual system selection (if no --job-id)
  --original <version>               Manual original version (if no --job-id)
  --upgraded <version>               Manual upgraded version (if no --job-id)
  --run-name <name>                  Explicit runner result folder name
  --rounds <N>                       Number of rounds (default: 1)
  --timeout-sec <N>                  Runner timeout in seconds (default: 3600)
  --clients <N>                      Number of clients (default: 1)
  --testing-mode <N>                 Upfuzz testing mode (default: 5)
  --diff-lane-timeout-sec <sec>      Differential lane timeout for all systems (default: 1200)
  --hbase-daemon-retry-times <N>     Override hbaseDaemonRetryTimes in generated config (HBase only)
  --node-num <N>                     Override node number (default for HBase jobs: 3)
  --skip-docker-build                Skip docker image build step
  --skip-build                       Skip './gradlew classes -x test'
  --skip-pull                        Deprecated alias for --skip-docker-build
  --list-jobs                        Print job-id mapping and exit
  -h, --help                         Show this help

Examples:
  run_cloudlab_job.sh --job-id 1
  run_cloudlab_job.sh --job-id 5 --run-name cloudlab_hdfs_2102_336
  run_cloudlab_job.sh --system cassandra --original apache-cassandra-4.1.10 --upgraded apache-cassandra-5.0.6
USAGE
}

log() {
    printf '[%s] %s\n' "$(date '+%F %T')" "$*"
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "Missing command: $1"
}

summary_value() {
    local key="$1"
    local file="$2"
    sed -n "s/^${key}: //p" "${file}" | head -n 1
}

validate_required_trace_signal() {
    local file="$1"
    local trace_signal_ok
    local merged_old
    local merged_rolling
    local merged_new

    trace_signal_ok="$(summary_value "trace_signal_ok" "${file}")"
    merged_old="$(summary_value "trace_merged_old_nonzero_count" "${file}")"
    merged_rolling="$(summary_value "trace_merged_rolling_nonzero_count" "${file}")"
    merged_new="$(summary_value "trace_merged_new_nonzero_count" "${file}")"

    [[ "${trace_signal_ok}" == "true" ]] \
        || die "Trace signal required but trace_signal_ok=${trace_signal_ok:-missing} (summary: ${file})"

    [[ "${merged_old}" =~ ^[0-9]+$ ]] \
        || die "Missing/invalid trace_merged_old_nonzero_count in ${file}: ${merged_old:-missing}"
    [[ "${merged_rolling}" =~ ^[0-9]+$ ]] \
        || die "Missing/invalid trace_merged_rolling_nonzero_count in ${file}: ${merged_rolling:-missing}"
    [[ "${merged_new}" =~ ^[0-9]+$ ]] \
        || die "Missing/invalid trace_merged_new_nonzero_count in ${file}: ${merged_new:-missing}"

    if (( merged_old < 1 || merged_rolling < 1 || merged_new < 1 )); then
        die "Trace signal required but merged nonzero counts are old=${merged_old}, rolling=${merged_rolling}, new=${merged_new} (summary: ${file})"
    fi
}

ensure_docker_compose() {
    if docker compose version >/dev/null 2>&1; then
        return 0
    fi

    if command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; then
        log "docker compose is missing; attempting to install docker-compose-v2"
        sudo -n apt-get update >/dev/null 2>&1 || true
        sudo -n apt-get install -y docker-compose-v2 >/dev/null 2>&1 || true
        if docker compose version >/dev/null 2>&1; then
            log "docker compose installed successfully"
            return 0
        fi
    fi

    die "docker compose is required. Install docker-compose-v2 (or docker-compose-plugin) and retry."
}

print_jobs() {
    cat <<'JOBS'
Job Mapping (6 machines):
  1 -> cassandra  apache-cassandra-3.11.19      -> apache-cassandra-4.1.10
  2 -> cassandra  apache-cassandra-4.1.10       -> apache-cassandra-5.0.6
  3 -> hbase      hbase-2.5.13                  -> hbase-2.6.4
  4 -> hbase      hbase-2.6.4                   -> hbase-4.0.0-alpha-1-SNAPSHOT
  5 -> hdfs       hadoop-2.10.2                 -> hadoop-3.3.6
  6 -> hdfs       hadoop-3.3.6                  -> hadoop-3.4.2
JOBS
}

assign_job() {
    case "$1" in
        1)
            SYSTEM="cassandra"
            ORIGINAL_VERSION="apache-cassandra-3.11.19"
            UPGRADED_VERSION="apache-cassandra-4.1.10"
            ;;
        2)
            SYSTEM="cassandra"
            ORIGINAL_VERSION="apache-cassandra-4.1.10"
            UPGRADED_VERSION="apache-cassandra-5.0.6"
            ;;
        3)
            SYSTEM="hbase"
            ORIGINAL_VERSION="hbase-2.5.13"
            UPGRADED_VERSION="hbase-2.6.4"
            ;;
        4)
            SYSTEM="hbase"
            ORIGINAL_VERSION="hbase-2.6.4"
            UPGRADED_VERSION="hbase-4.0.0-alpha-1-SNAPSHOT"
            ;;
        5)
            SYSTEM="hdfs"
            ORIGINAL_VERSION="hadoop-2.10.2"
            UPGRADED_VERSION="hadoop-3.3.6"
            ;;
        6)
            SYSTEM="hdfs"
            ORIGINAL_VERSION="hadoop-3.3.6"
            UPGRADED_VERSION="hadoop-3.4.2"
            ;;
        *)
            die "Unsupported --job-id: $1 (expected 1..6)"
            ;;
    esac
}

ensure_hdfs_tmp_root_writable() {
    local root="/tmp/upfuzz/hdfs"

    mkdir -p "${root}" 2>/dev/null || true
    if [[ ! -d "${root}" ]]; then
        if command -v sudo >/dev/null 2>&1; then
            sudo -n mkdir -p "${root}" 2>/dev/null || true
        fi
    fi

    if [[ -d "${root}" && ! -w "${root}" ]]; then
        if command -v sudo >/dev/null 2>&1; then
            sudo -n chown -R "$(id -u):$(id -g)" /tmp/upfuzz 2>/dev/null || true
            sudo -n chmod -R u+rwx /tmp/upfuzz 2>/dev/null || true
        fi
    fi

    [[ -d "${root}" && -w "${root}" ]] || die "Path not writable: ${root}. Fix ownership/permissions before running HDFS jobs."
}

ensure_hdfs_example_files() {
    local dir="${ROOT_DIR}/examplecase"
    local plan="${dir}/testplan_hdfs_example.txt"
    local valid="${dir}/validcommands_hdfs_example.txt"

    mkdir -p "${dir}"

    if [[ ! -f "${plan}" ]]; then
        cat > "${plan}" <<'EOF'
[Command] Execute {dfs -mkdir /upfuzz_demo}
[Command] Execute {dfs -touchz /upfuzz_demo/a.txt}
[Command] Execute {dfs -ls /upfuzz_demo}
[UpgradeOp] Upgrade Node[0]
[Command] Execute {dfs -ls /upfuzz_demo}
[UpgradeOp] Upgrade Node[1]
[UpgradeOp] Upgrade Node[2]
[Command] Execute {dfs -count -q -h /upfuzz_demo}
EOF
    fi

    if [[ ! -f "${valid}" ]]; then
        cat > "${valid}" <<'EOF'
dfs -ls /upfuzz_demo
EOF
    fi
}

resolve_java11_home() {
    local candidates=(
        "${JAVA11_HOME:-}"
        "/usr/lib/jvm/java-11-openjdk-amd64"
        "/usr/lib/jvm/java-11-openjdk"
    )
    local c
    for c in "${candidates[@]}"; do
        if [[ -n "${c}" && -x "${c}/bin/java" ]]; then
            echo "${c}"
            return 0
        fi
    done
    return 1
}

build_required_images() {
    [[ -x "${DOCKER_BUILD_SCRIPT}" ]] || die "Missing or non-executable docker build script: ${DOCKER_BUILD_SCRIPT}"

    log "Building docker image pair via ${DOCKER_BUILD_SCRIPT}"
    (
        cd "${ROOT_DIR}"
        UPFUZZ_DIR="${ROOT_DIR}" "${DOCKER_BUILD_SCRIPT}" "${SYSTEM}" "${ORIGINAL_VERSION}" "${UPGRADED_VERSION}"
    )
}

ensure_bidirectional_image_tags() {
    local forward_img="upfuzz_${SYSTEM}:${ORIGINAL_VERSION}_${UPGRADED_VERSION}"
    local reverse_img="upfuzz_${SYSTEM}:${UPGRADED_VERSION}_${ORIGINAL_VERSION}"

    docker image inspect "${forward_img}" >/dev/null 2>&1 \
        || die "Missing local image tag: ${forward_img}"

    # Differential runs may create one executor with reversed version order.
    # The same local image supports both orderings, so ensure both tags exist.
    docker tag "${forward_img}" "${reverse_img}"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --job-id)
            JOB_ID="$2"
            shift 2
            ;;
        --system)
            SYSTEM="$2"
            shift 2
            ;;
        --original)
            ORIGINAL_VERSION="$2"
            shift 2
            ;;
        --upgraded)
            UPGRADED_VERSION="$2"
            shift 2
            ;;
        --run-name)
            RUN_NAME="$2"
            shift 2
            ;;
        --rounds)
            ROUNDS="$2"
            shift 2
            ;;
        --timeout-sec)
            TIMEOUT_SEC="$2"
            shift 2
            ;;
        --clients)
            CLIENTS="$2"
            shift 2
            ;;
        --testing-mode)
            TESTING_MODE="$2"
            shift 2
            ;;
        --diff-lane-timeout-sec)
            DIFF_LANE_TIMEOUT_SEC="$2"
            shift 2
            ;;
        --hbase-daemon-retry-times)
            HBASE_DAEMON_RETRY_TIMES="$2"
            shift 2
            ;;
        --cassandra-retry-timeout)
            # Backward-compatible alias for old launcher calls.
            DIFF_LANE_TIMEOUT_SEC="$2"
            shift 2
            ;;
        --node-num)
            NODE_NUM="$2"
            shift 2
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift 1
            ;;
        --skip-docker-build)
            SKIP_DOCKER_BUILD=true
            shift 1
            ;;
        --skip-pull)
            SKIP_DOCKER_BUILD=true
            shift 1
            ;;
        --list-jobs)
            print_jobs
            exit 0
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

require_cmd docker
ensure_docker_compose

if [[ -n "${JOB_ID}" ]]; then
    assign_job "${JOB_ID}"
fi

[[ -n "${SYSTEM}" ]] || die "Missing target system. Provide --job-id or --system/--original/--upgraded."
[[ -n "${ORIGINAL_VERSION}" ]] || die "Missing --original version."
[[ -n "${UPGRADED_VERSION}" ]] || die "Missing --upgraded version."

case "${SYSTEM}" in
    cassandra)
        [[ "${ORIGINAL_VERSION}" == apache-cassandra-* ]] || die "Invalid Cassandra original version: ${ORIGINAL_VERSION}"
        [[ "${UPGRADED_VERSION}" == apache-cassandra-* ]] || die "Invalid Cassandra upgraded version: ${UPGRADED_VERSION}"
        ;;
    hbase)
        [[ "${ORIGINAL_VERSION}" == hbase-* ]] || die "Invalid HBase original version: ${ORIGINAL_VERSION}"
        [[ "${UPGRADED_VERSION}" == hbase-* ]] || die "Invalid HBase upgraded version: ${UPGRADED_VERSION}"
        [[ -n "${NODE_NUM}" ]] || NODE_NUM=3
        ;;
    hdfs)
        [[ "${ORIGINAL_VERSION}" == hadoop-* ]] || die "Invalid HDFS original version: ${ORIGINAL_VERSION}"
        [[ "${UPGRADED_VERSION}" == hadoop-* ]] || die "Invalid HDFS upgraded version: ${UPGRADED_VERSION}"
        ;;
    *)
        die "Unsupported system: ${SYSTEM}"
        ;;
esac

if [[ -z "${RUN_NAME}" ]]; then
    RUN_NAME="${SYSTEM}_${ORIGINAL_VERSION}_to_${UPGRADED_VERSION}_cloudlab_$(date '+%Y%m%d_%H%M%S')"
fi

LAUNCH_DIR="${RESULTS_ROOT}/${RUN_NAME}"
mkdir -p "${LAUNCH_DIR}"
LAUNCH_LOG="${LAUNCH_DIR}/launch.log"

log "Job setup: ${SYSTEM} ${ORIGINAL_VERSION} -> ${UPGRADED_VERSION}" | tee -a "${LAUNCH_LOG}"

if [[ "${SKIP_DOCKER_BUILD}" == false ]]; then
    build_required_images 2>&1 | tee -a "${LAUNCH_LOG}"
else
    log "Skipping docker image build (--skip-docker-build/--skip-pull)" | tee -a "${LAUNCH_LOG}"
fi

if [[ "${SKIP_BUILD}" == false ]]; then
    JAVA11_BUILD_HOME="$(resolve_java11_home)" || die "Java 11 not found. Install openjdk-11-jdk or set JAVA11_HOME."
    log "Preparing runtime dependencies (./gradlew copyDependencies)" | tee -a "${LAUNCH_LOG}"
    (
        cd "${ROOT_DIR}"
        JAVA_HOME="${JAVA11_BUILD_HOME}" PATH="${JAVA11_BUILD_HOME}/bin:${PATH}" ./gradlew copyDependencies
    ) 2>&1 | tee -a "${LAUNCH_LOG}"

    log "Building Java classes (./gradlew classes -x test)" | tee -a "${LAUNCH_LOG}"
    (
        cd "${ROOT_DIR}"
        JAVA_HOME="${JAVA11_BUILD_HOME}" PATH="${JAVA11_BUILD_HOME}/bin:${PATH}" ./gradlew classes -x test
    ) 2>&1 | tee -a "${LAUNCH_LOG}"
else
    log "Skipping Java build (--skip-build)" | tee -a "${LAUNCH_LOG}"
fi

ensure_bidirectional_image_tags

if [[ "${SYSTEM}" == "hdfs" ]]; then
    ensure_hdfs_tmp_root_writable
    ensure_hdfs_example_files
fi

RUNNER_CMD=(
    "${RUNNER_SCRIPT}"
    --system "${SYSTEM}"
    --original "${ORIGINAL_VERSION}"
    --upgraded "${UPGRADED_VERSION}"
    --rounds "${ROUNDS}"
    --timeout-sec "${TIMEOUT_SEC}"
    --clients "${CLIENTS}"
    --testing-mode "${TESTING_MODE}"
    --diff-lane-timeout-sec "${DIFF_LANE_TIMEOUT_SEC}"
    --run-name "${RUN_NAME}"
)
# Mode-dependent trace arguments
if [[ "${TESTING_MODE}" == "6" ]]; then
    RUNNER_CMD+=(--use-trace false --print-trace false)
else
    RUNNER_CMD+=(--use-trace true --print-trace true --require-trace-signal)
fi
if [[ -n "${NODE_NUM}" ]]; then
    RUNNER_CMD+=(--node-num "${NODE_NUM}")
fi
if [[ "${SYSTEM}" == "hbase" && -n "${HBASE_DAEMON_RETRY_TIMES}" ]]; then
    RUNNER_CMD+=(--hbase-daemon-retry-times "${HBASE_DAEMON_RETRY_TIMES}")
fi

# Snapshot candidate counts before runner so phase6_summary reflects this run only
_cand_dir="${ROOT_DIR}/failure/candidate"
_strong_before=0
_weak_before=0
if [[ -d "${_cand_dir}/strong" ]]; then
    _strong_before="$(find "${_cand_dir}/strong" -mindepth 1 -maxdepth 1 -type d -name 'failure_*' 2>/dev/null | wc -l | tr -d ' ')"
fi
if [[ -d "${_cand_dir}/weak" ]]; then
    _weak_before="$(find "${_cand_dir}/weak" -mindepth 1 -maxdepth 1 -type d -name 'failure_*' 2>/dev/null | wc -l | tr -d ' ')"
fi

log "Launching: ${RUNNER_CMD[*]}" | tee -a "${LAUNCH_LOG}"
set +e
(
    cd "${ROOT_DIR}"
    "${RUNNER_CMD[@]}"
) 2>&1 | tee -a "${LAUNCH_LOG}"
RUNNER_RC=${PIPESTATUS[0]}
set -e

RUNNER_RESULT_DIR="${ROOT_DIR}/scripts/runner/results/${RUN_NAME}"
SUMMARY_FILE="${RUNNER_RESULT_DIR}/summary.txt"

if [[ -f "${SUMMARY_FILE}" ]]; then
    cp -f "${SUMMARY_FILE}" "${LAUNCH_DIR}/summary.txt"
    for f in config.json server_stdout.log client_launcher_stdout.log upfuzz_server.log upfuzz_client_1.log monitor.log server_key_markers.log client_key_markers.log; do
        [[ -f "${RUNNER_RESULT_DIR}/${f}" ]] && cp -f "${RUNNER_RESULT_DIR}/${f}" "${LAUNCH_DIR}/${f}"
    done

    # Phase 6: copy observability artifacts
    if [[ -d "${RUNNER_RESULT_DIR}/observability" ]]; then
        mkdir -p "${LAUNCH_DIR}/observability"
        cp -f "${RUNNER_RESULT_DIR}/observability/"*.csv "${LAUNCH_DIR}/observability/" 2>/dev/null || true
    fi

    # Phase 6: count run-local strong/weak candidates (after - before snapshot)
    _strong_after=0
    _weak_after=0
    if [[ -d "${_cand_dir}/strong" ]]; then
        _strong_after="$(find "${_cand_dir}/strong" -mindepth 1 -maxdepth 1 -type d -name 'failure_*' 2>/dev/null | wc -l | tr -d ' ')"
    fi
    if [[ -d "${_cand_dir}/weak" ]]; then
        _weak_after="$(find "${_cand_dir}/weak" -mindepth 1 -maxdepth 1 -type d -name 'failure_*' 2>/dev/null | wc -l | tr -d ' ')"
    fi
    _strong_cand=$((_strong_after - _strong_before))
    _weak_cand=$((_weak_after - _weak_before))
    (( _strong_cand < 0 )) && _strong_cand=0
    (( _weak_cand < 0 )) && _weak_cand=0

    _obs_dir="${LAUNCH_DIR}/observability"
    _obs_present=""
    _obs_missing=""
    for _csv in trace_admission_summary.csv trace_window_summary.csv \
                seed_lifecycle_summary.csv queue_activity_summary.csv \
                scheduler_metrics_summary.csv branch_novelty_summary.csv \
                stage_novelty_summary.csv; do
        if [[ -f "${_obs_dir}/${_csv}" ]]; then
            _obs_present="${_obs_present:+${_obs_present}, }${_csv}"
        else
            _obs_missing="${_obs_missing:+${_obs_missing}, }${_csv}"
        fi
    done

    _git_sha="$(cd "${ROOT_DIR}" && git rev-parse --short HEAD 2>/dev/null || echo 'unknown')"

    cat > "${LAUNCH_DIR}/phase6_summary.txt" <<P6SUM
git_sha: ${_git_sha}
testing_mode: ${TESTING_MODE}
system: ${SYSTEM}
original_version: ${ORIGINAL_VERSION}
upgraded_version: ${UPGRADED_VERSION}
strong_candidates: ${_strong_cand}
weak_candidates: ${_weak_cand}
candidate_dir: ${_cand_dir}
observability_present: ${_obs_present:-none}
observability_missing: ${_obs_missing:-none}
P6SUM

    log "Run complete. Summary: ${SUMMARY_FILE}" | tee -a "${LAUNCH_LOG}"
    egrep "^(system:|original_version:|upgraded_version:|observed_rounds:|diff_feedback_packets:|stop_reason:|trace_signal_ok:|trace_len_positive_count:|trace_len_zero_count:|trace_merged_old_nonzero_count:|trace_merged_rolling_nonzero_count:|trace_merged_new_nonzero_count:|trace_merged_zero_count:|message_tri_diff_count:|trace_connect_refused_count:)" \
        "${SUMMARY_FILE}" | tee -a "${LAUNCH_LOG}"

    if (( RUNNER_RC != 0 )); then
        die "Runner exited with code ${RUNNER_RC}. See ${SUMMARY_FILE} and ${LAUNCH_LOG}."
    fi

    if [[ "${TESTING_MODE}" == "6" ]]; then
        log "Mode 6: skipping trace signal validation (branch-only)." | tee -a "${LAUNCH_LOG}"
    else
        validate_required_trace_signal "${SUMMARY_FILE}"
        log "Trace signal requirement satisfied." | tee -a "${LAUNCH_LOG}"
    fi
else
    die "Runner finished without summary file: ${SUMMARY_FILE} (runner_rc=${RUNNER_RC})"
fi
