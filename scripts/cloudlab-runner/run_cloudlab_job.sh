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
ROLLING_GENERATION_POLICY="guided"
NODE_NUM=""
DIFF_LANE_TIMEOUT_SEC=1200
HBASE_DAEMON_RETRY_TIMES=""
SKIP_BUILD=false
SKIP_DOCKER_BUILD=false
DRY_RUN=false
ENABLE_CHECKPOINT_RESTORE=false
CHECKPOINT_SELECTED_NODES="0"
CHECKPOINT_ALL_LANES=true
CHECKPOINT_CACHE_DIR="fuzzing_storage/checkpoints"
CHECKPOINT_REUSE=false
CHECKPOINT_ALLOW_NON_CASSANDRA=false
CHECKPOINT_WORKLOAD_ONLY_BENCHMARK=false
VERIFY_CONFIG=true
TEST_BOUNDARY_CONFIG=true
TEST_ADDED_CONFIG=true
TEST_DELETED_CONFIG=true
TEST_COMMON_CONFIG=true
TEST_REMAIN_CONFIG=true
TEST_BOUNDARY_UPGRADE_CONFIG_RATIO=1
TEST_UPGRADE_CONFIG_RATIO=0.4
TEST_REMAIN_UPGRADE_CONFIG_RATIO=0.4

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
  --rolling-generation-policy <guided|pure_random>
                                     Mode-5/6 input generation policy (default: guided)
  --diff-lane-timeout-sec <sec>      Differential lane timeout for all systems (default: 1200)
  --hbase-daemon-retry-times <N>     Override hbaseDaemonRetryTimes in generated config (HBase only)
  --node-num <N>                     Override node number (default for HBase jobs: 3)
  --enable-checkpoint-restore <true|false>
                                     Enable mode-5 Docker checkpoint startup path (default: false)
  --checkpoint-reuse <true|false>    Reuse persistent checkpoint cache images; requires checkpoint restore (default: false)
  --checkpoint-selected-nodes <csv>  Node indexes for checkpoint prefix, e.g. 0 or 0,1 (default: 0)
  --checkpoint-all-lanes <true|false>
                                     Apply checkpoint prefix to old-old, rolling, and new-new lanes (default: true)
  --checkpoint-cache-dir <path>      Checkpoint metadata/cache directory passed to UpFuzz
  --checkpoint-allow-non-cassandra <true|false>
                                     Allow checkpoint mode for HDFS/HBase after validation (default: false)
  --checkpoint-workload-only-benchmark <true|false>
                                     Deprecated compatibility knob passed through to UpFuzz (default: false)
  --enable-file-config-mutator       Enable boundary/added/deleted/common/remain config mutation (default)
  --disable-file-config-mutator      Disable file-level config mutation and config verification
  --verify-config <true|false>       Verify generated config before execution (default: true)
  --test-boundary-config <true|false>
                                     Mutate boundary-related upgrade configs (default: true)
  --test-added-config <true|false>   Mutate configs added by upgraded version (default: true)
  --test-deleted-config <true|false> Mutate configs deleted from upgraded version (default: true)
  --test-common-config <true|false>  Mutate configs common to both versions (default: true)
  --test-remain-config <true|false>  Mutate remaining old/new configs (default: true)
  --test-boundary-upgrade-config-ratio <N>
                                     Boundary config mutation ratio (default: 1)
  --test-upgrade-config-ratio <N>    Added/deleted/common config mutation ratio (default: 0.4)
  --test-remain-upgrade-config-ratio <N>
                                     Remaining config mutation ratio (default: 0.4)
  --dry-run                          Mock a CloudLab launch locally: validate and print runner command only
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

validate_bool() {
    local name="$1"
    local value="$2"
    case "${value}" in
        true|false) ;;
        *) die "${name} must be true|false (got: ${value})" ;;
    esac
}

validate_nonnegative_number() {
    local name="$1"
    local value="$2"
    [[ "${value}" =~ ^([0-9]+([.][0-9]+)?|[.][0-9]+)$ ]] \
        || die "${name} must be a non-negative number (got: ${value})"
}

enable_file_config_mutator() {
    VERIFY_CONFIG=true
    TEST_BOUNDARY_CONFIG=true
    TEST_ADDED_CONFIG=true
    TEST_DELETED_CONFIG=true
    TEST_COMMON_CONFIG=true
    TEST_REMAIN_CONFIG=true
}

disable_file_config_mutator() {
    VERIFY_CONFIG=false
    TEST_BOUNDARY_CONFIG=false
    TEST_ADDED_CONFIG=false
    TEST_DELETED_CONFIG=false
    TEST_COMMON_CONFIG=false
    TEST_REMAIN_CONFIG=false
}

render_cmd() {
    local rendered=""
    local arg
    for arg in "$@"; do
        rendered+=" $(printf '%q' "${arg}")"
    done
    echo "${rendered# }"
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
        --rolling-generation-policy)
            ROLLING_GENERATION_POLICY="$2"
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
        --enable-checkpoint-restore)
            ENABLE_CHECKPOINT_RESTORE="$2"
            shift 2
            ;;
        --checkpoint-reuse)
            CHECKPOINT_REUSE="$2"
            shift 2
            ;;
        --checkpoint-selected-nodes)
            CHECKPOINT_SELECTED_NODES="$2"
            shift 2
            ;;
        --checkpoint-all-lanes)
            CHECKPOINT_ALL_LANES="$2"
            shift 2
            ;;
        --checkpoint-cache-dir)
            CHECKPOINT_CACHE_DIR="$2"
            shift 2
            ;;
        --checkpoint-allow-non-cassandra)
            CHECKPOINT_ALLOW_NON_CASSANDRA="$2"
            shift 2
            ;;
        --checkpoint-workload-only-benchmark)
            CHECKPOINT_WORKLOAD_ONLY_BENCHMARK="$2"
            shift 2
            ;;
        --enable-file-config-mutator)
            enable_file_config_mutator
            shift 1
            ;;
        --disable-file-config-mutator)
            disable_file_config_mutator
            shift 1
            ;;
        --verify-config)
            VERIFY_CONFIG="$2"
            shift 2
            ;;
        --test-boundary-config)
            TEST_BOUNDARY_CONFIG="$2"
            shift 2
            ;;
        --test-added-config)
            TEST_ADDED_CONFIG="$2"
            shift 2
            ;;
        --test-deleted-config)
            TEST_DELETED_CONFIG="$2"
            shift 2
            ;;
        --test-common-config)
            TEST_COMMON_CONFIG="$2"
            shift 2
            ;;
        --test-remain-config)
            TEST_REMAIN_CONFIG="$2"
            shift 2
            ;;
        --test-boundary-upgrade-config-ratio)
            TEST_BOUNDARY_UPGRADE_CONFIG_RATIO="$2"
            shift 2
            ;;
        --test-upgrade-config-ratio)
            TEST_UPGRADE_CONFIG_RATIO="$2"
            shift 2
            ;;
        --test-remain-upgrade-config-ratio)
            TEST_REMAIN_UPGRADE_CONFIG_RATIO="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift 1
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

validate_bool "--enable-checkpoint-restore" "${ENABLE_CHECKPOINT_RESTORE}"
validate_bool "--checkpoint-reuse" "${CHECKPOINT_REUSE}"
validate_bool "--checkpoint-all-lanes" "${CHECKPOINT_ALL_LANES}"
validate_bool "--checkpoint-allow-non-cassandra" "${CHECKPOINT_ALLOW_NON_CASSANDRA}"
validate_bool "--checkpoint-workload-only-benchmark" "${CHECKPOINT_WORKLOAD_ONLY_BENCHMARK}"
validate_bool "--verify-config" "${VERIFY_CONFIG}"
validate_bool "--test-boundary-config" "${TEST_BOUNDARY_CONFIG}"
validate_bool "--test-added-config" "${TEST_ADDED_CONFIG}"
validate_bool "--test-deleted-config" "${TEST_DELETED_CONFIG}"
validate_bool "--test-common-config" "${TEST_COMMON_CONFIG}"
validate_bool "--test-remain-config" "${TEST_REMAIN_CONFIG}"
validate_nonnegative_number "--test-boundary-upgrade-config-ratio" "${TEST_BOUNDARY_UPGRADE_CONFIG_RATIO}"
validate_nonnegative_number "--test-upgrade-config-ratio" "${TEST_UPGRADE_CONFIG_RATIO}"
validate_nonnegative_number "--test-remain-upgrade-config-ratio" "${TEST_REMAIN_UPGRADE_CONFIG_RATIO}"

case "${ROLLING_GENERATION_POLICY}" in
    guided|pure_random) ;;
    *) die "--rolling-generation-policy must be guided|pure_random (got: ${ROLLING_GENERATION_POLICY})" ;;
esac
if [[ "${ROLLING_GENERATION_POLICY}" == "pure_random" \
        && "${TESTING_MODE}" != "5" \
        && "${TESTING_MODE}" != "6" ]]; then
    die "--rolling-generation-policy pure_random is only supported with --testing-mode 5 or 6"
fi

if [[ "${CHECKPOINT_REUSE}" == true && "${ENABLE_CHECKPOINT_RESTORE}" != true ]]; then
    die "--checkpoint-reuse true requires --enable-checkpoint-restore true"
fi
if [[ "${ENABLE_CHECKPOINT_RESTORE}" == true ]]; then
    [[ "${TESTING_MODE}" == "5" ]] || die "--enable-checkpoint-restore is only supported with --testing-mode 5"
    if [[ "${SYSTEM}" != "cassandra" && "${CHECKPOINT_ALLOW_NON_CASSANDRA}" != true ]]; then
        die "--enable-checkpoint-restore for ${SYSTEM} requires --checkpoint-allow-non-cassandra true"
    fi
fi

if [[ -z "${RUN_NAME}" ]]; then
    RUN_NAME="${SYSTEM}_${ORIGINAL_VERSION}_to_${UPGRADED_VERSION}_cloudlab_$(date '+%Y%m%d_%H%M%S')"
fi

LAUNCH_DIR="${RESULTS_ROOT}/${RUN_NAME}"
mkdir -p "${LAUNCH_DIR}"
LAUNCH_LOG="${LAUNCH_DIR}/launch.log"

log "Job setup: ${SYSTEM} ${ORIGINAL_VERSION} -> ${UPGRADED_VERSION}" | tee -a "${LAUNCH_LOG}"

if [[ "${DRY_RUN}" == false ]]; then
    require_cmd docker
    ensure_docker_compose

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
else
    log "Dry run: skipping Docker build/checks, Java build, HDFS temp setup, and runner execution" | tee -a "${LAUNCH_LOG}"
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
    --rolling-generation-policy "${ROLLING_GENERATION_POLICY}"
    --diff-lane-timeout-sec "${DIFF_LANE_TIMEOUT_SEC}"
    --enable-checkpoint-restore "${ENABLE_CHECKPOINT_RESTORE}"
    --checkpoint-reuse "${CHECKPOINT_REUSE}"
    --checkpoint-selected-nodes "${CHECKPOINT_SELECTED_NODES}"
    --checkpoint-all-lanes "${CHECKPOINT_ALL_LANES}"
    --checkpoint-cache-dir "${CHECKPOINT_CACHE_DIR}"
    --checkpoint-allow-non-cassandra "${CHECKPOINT_ALLOW_NON_CASSANDRA}"
    --checkpoint-workload-only-benchmark "${CHECKPOINT_WORKLOAD_ONLY_BENCHMARK}"
    --verify-config "${VERIFY_CONFIG}"
    --test-boundary-config "${TEST_BOUNDARY_CONFIG}"
    --test-added-config "${TEST_ADDED_CONFIG}"
    --test-deleted-config "${TEST_DELETED_CONFIG}"
    --test-common-config "${TEST_COMMON_CONFIG}"
    --test-remain-config "${TEST_REMAIN_CONFIG}"
    --test-boundary-upgrade-config-ratio "${TEST_BOUNDARY_UPGRADE_CONFIG_RATIO}"
    --test-upgrade-config-ratio "${TEST_UPGRADE_CONFIG_RATIO}"
    --test-remain-upgrade-config-ratio "${TEST_REMAIN_UPGRADE_CONFIG_RATIO}"
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

if [[ "${DRY_RUN}" == true ]]; then
    rendered_runner_cmd="$(render_cmd "${RUNNER_CMD[@]}")"
    printf '%s\n' "${rendered_runner_cmd}" > "${LAUNCH_DIR}/dry_run_runner_cmd.txt"
    cat > "${LAUNCH_DIR}/dry_run_summary.txt" <<DRYSUM
dry_run: true
system: ${SYSTEM}
original_version: ${ORIGINAL_VERSION}
upgraded_version: ${UPGRADED_VERSION}
testing_mode: ${TESTING_MODE}
rolling_generation_policy: ${ROLLING_GENERATION_POLICY}
enable_checkpoint_restore: ${ENABLE_CHECKPOINT_RESTORE}
checkpoint_reuse: ${CHECKPOINT_REUSE}
checkpoint_selected_nodes: ${CHECKPOINT_SELECTED_NODES}
checkpoint_all_lanes: ${CHECKPOINT_ALL_LANES}
checkpoint_allow_non_cassandra: ${CHECKPOINT_ALLOW_NON_CASSANDRA}
checkpoint_workload_only_benchmark: ${CHECKPOINT_WORKLOAD_ONLY_BENCHMARK}
verify_config: ${VERIFY_CONFIG}
test_boundary_config: ${TEST_BOUNDARY_CONFIG}
test_added_config: ${TEST_ADDED_CONFIG}
test_deleted_config: ${TEST_DELETED_CONFIG}
test_common_config: ${TEST_COMMON_CONFIG}
test_remain_config: ${TEST_REMAIN_CONFIG}
test_boundary_upgrade_config_ratio: ${TEST_BOUNDARY_UPGRADE_CONFIG_RATIO}
test_upgrade_config_ratio: ${TEST_UPGRADE_CONFIG_RATIO}
test_remain_upgrade_config_ratio: ${TEST_REMAIN_UPGRADE_CONFIG_RATIO}
runner_command_file: ${LAUNCH_DIR}/dry_run_runner_cmd.txt
DRYSUM
    log "Dry-run runner command: ${rendered_runner_cmd}" | tee -a "${LAUNCH_LOG}"
    log "Dry-run summary: ${LAUNCH_DIR}/dry_run_summary.txt" | tee -a "${LAUNCH_LOG}"
    exit 0
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
rolling_generation_policy: ${ROLLING_GENERATION_POLICY}
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
