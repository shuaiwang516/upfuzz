#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RESULTS_ROOT="${SCRIPT_DIR}/results"
mkdir -p "${RESULTS_ROOT}"

SYSTEM="cassandra"
ORIGINAL_VERSION="apache-cassandra-4.1.10"
UPGRADED_VERSION="apache-cassandra-5.0.6"
TARGET_ROUNDS=2
TIMEOUT_SEC=3600
CLIENTS=1
TESTING_MODE=3
USE_DIFF=true
USE_TRACE=true
USE_JACCARD=true
USE_BRANCH_COVERAGE=true
ENABLE_LOG_CHECK=true
REQUIRE_TRACE_SIGNAL=false
CASSANDRA_RETRY_TIMEOUT=300
NODE_NUM=""
SERVER_PORT=7399
CLIENT_PORT=7400
RUN_NAME=""
SKIP_PRE_CLEAN=false

usage() {
    cat <<USAGE
Usage:
  $(basename "$0") [options]

Options:
  --system <cassandra|hdfs|hbase>       Target system (default: ${SYSTEM})
  --original <version>                   Original version tag
  --upgraded <version>                   Upgraded version tag
  --rounds <N>                           Stop after N completed rounds (default: ${TARGET_ROUNDS})
  --timeout-sec <N>                      Max runtime in seconds (default: ${TIMEOUT_SEC})
  --clients <N>                          Number of clients to launch (default: ${CLIENTS})
  --testing-mode <3|5>                   3=example testplan, 5=rolling-only (default: ${TESTING_MODE})
  --cassandra-retry-timeout <sec>        Cassandra cqlsh retry timeout (default: ${CASSANDRA_RETRY_TIMEOUT})
  --use-trace <true|false>               Enable network trace collection (default: ${USE_TRACE})
  --use-jaccard <true|false>             Enable Jaccard similarity (default: ${USE_JACCARD})
  --use-branch-coverage <true|false>     Enable branch coverage signals (default: ${USE_BRANCH_COVERAGE})
  --enable-log-check <true|false>        Enable error-log oracle (default: ${ENABLE_LOG_CHECK})
  --require-trace-signal                 Fail if trace signal is missing when --use-trace=true
  --server-port <port>                   Server port (default: ${SERVER_PORT}, auto-shift if busy)
  --client-port <port>                   Client port (default: ${CLIENT_PORT}, auto-shift if busy)
  --node-num <N>                         Override node number (default by system: cass=2,hdfs=3,hbase=2)
  --run-name <name>                      Result folder name (default: auto generated)
  --skip-pre-clean                       Skip pre-run clean.sh
  -h, --help                             Show this help

Examples:
  $(basename "$0") \
    --system cassandra \
    --original apache-cassandra-4.1.10 \
    --upgraded apache-cassandra-5.0.6 \
    --rounds 2
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

bool_json() {
    if [[ "$1" == true ]]; then
        echo "true"
    else
        echo "false"
    fi
}

is_port_in_use() {
    local port="$1"
    ss -ltn 2>/dev/null | awk '{print $4}' | rg -q "(^|[:.])${port}$"
}

pick_free_port_from() {
    local port="$1"
    while is_port_in_use "${port}"; do
        port=$((port + 1))
    done
    echo "${port}"
}

sanitize_name() {
    echo "$1" | sed 's/[^a-zA-Z0-9._-]/_/g'
}

safe_rg_count_file() {
    local pattern="$1"
    local logfile="$2"
    if [[ ! -f "${logfile}" ]]; then
        echo 0
        return
    fi
    local cnt
    cnt="$(rg -c --no-filename "${pattern}" "${logfile}" 2>/dev/null || true)"
    if [[ -z "${cnt}" ]]; then
        cnt=0
    fi
    echo "${cnt}"
}

count_pattern_in_clients() {
    local pattern="$1"
    local total=0
    local i
    for i in $(seq 1 "${CLIENTS}"); do
        local logfile="${CLIENT_LOG_PREFIX}_${i}.log"
        local cnt
        cnt="$(safe_rg_count_file "${pattern}" "${logfile}")"
        total=$((total + cnt))
    done
    echo "${total}"
}

extract_total_exec() {
    local logfile="$1"
    if [[ ! -f "${logfile}" ]]; then
        echo 0
        return
    fi

    local max_exec
    max_exec="$(rg -o --no-filename 'total exec : [0-9]+' "${logfile}" 2>/dev/null | awk '{print $4}' | sort -n | tail -n1 || true)"
    if [[ -n "${max_exec}" ]]; then
        echo "${max_exec}"
        return
    fi

    local diff_count
    diff_count="$(rg -c --no-filename 'TestPlanDiffFeedbackPacket received' "${logfile}" 2>/dev/null || true)"
    echo "${diff_count:-0}"
}

count_diff_feedback_packets() {
    local logfile="$1"
    if [[ ! -f "${logfile}" ]]; then
        echo 0
        return
    fi
    rg -c --no-filename 'TestPlanDiffFeedbackPacket received' "${logfile}" 2>/dev/null || echo 0
}

write_config_json() {
    local path="$1"
    local system="$2"
    local original="$3"
    local upgraded="$4"
    local node_num="$5"
    local server_port="$6"
    local client_port="$7"

    local diff_json
    local trace_json
    local jaccard_json
    local branch_json
    local logcheck_json
    diff_json="$(bool_json "${USE_DIFF}")"
    trace_json="$(bool_json "${USE_TRACE}")"
    jaccard_json="$(bool_json "${USE_JACCARD}")"
    branch_json="$(bool_json "${USE_BRANCH_COVERAGE}")"
    logcheck_json="$(bool_json "${ENABLE_LOG_CHECK}")"

    case "${system}" in
        cassandra)
            cat > "${path}" <<JSON
{
  "originalVersion" : "${original}",
  "upgradedVersion" : "${upgraded}",
  "system" : "cassandra",
  "serverPort" : ${server_port},
  "clientPort" : ${client_port},
  "configDir" : "configtests",
  "STACKED_TESTS_NUM" : 1,
  "STACKED_TESTS_NUM_G2" : 1,
  "DROP_TEST_PROB_G2" : 0.1,
  "sequenceMutationEpoch" : 80,
  "nodeNum" : ${node_num},
  "faultMaxNum" : 0,
  "loadInitCorpus" : false,
  "saveCorpusToDisk" : true,
  "testSingleVersion" : false,
  "testingMode" : ${TESTING_MODE},
  "differentialExecution" : ${diff_json},
  "useTrace" : ${trace_json},
  "printTrace" : false,
  "useJaccardSimilarity" : ${jaccard_json},
  "jaccardSimilarityThreshold" : 0.3,
  "useBranchCoverage" : ${branch_json},
  "enableLogCheck" : ${logcheck_json},
  "useFormatCoverage" : false,
  "useVersionDelta" : false,
  "verifyConfig" : false,
  "testBoundaryConfig" : false,
  "testAddedConfig" : false,
  "testDeletedConfig" : false,
  "testCommonConfig" : false,
  "testRemainConfig" : false,
  "nyxMode" : false,
  "debug" : false,
  "useExampleTestPlan" : false,
  "startUpClusterForDebugging" : false,
  "drain" : false,
  "useFixedCommand" : false,
  "enable_ORDERBY_IN_SELECT" : true,
  "cassandraEnableTimeoutCheck" : false,
  "CASSANDRA_RETRY_TIMEOUT" : ${CASSANDRA_RETRY_TIMEOUT}
}
JSON
            ;;
        hdfs)
            cat > "${path}" <<JSON
{
  "originalVersion" : "${original}",
  "upgradedVersion" : "${upgraded}",
  "system" : "hdfs",
  "serverPort" : ${server_port},
  "clientPort" : ${client_port},
  "configDir" : "configtests",
  "STACKED_TESTS_NUM" : 1,
  "STACKED_TESTS_NUM_G2" : 1,
  "DROP_TEST_PROB_G2" : 0.1,
  "sequenceMutationEpoch" : 80,
  "nodeNum" : ${node_num},
  "faultMaxNum" : 0,
  "loadInitCorpus" : false,
  "saveCorpusToDisk" : true,
  "testSingleVersion" : false,
  "testingMode" : ${TESTING_MODE},
  "differentialExecution" : ${diff_json},
  "useTrace" : ${trace_json},
  "printTrace" : false,
  "useJaccardSimilarity" : ${jaccard_json},
  "jaccardSimilarityThreshold" : 0.3,
  "useBranchCoverage" : ${branch_json},
  "enableLogCheck" : ${logcheck_json},
  "useFormatCoverage" : false,
  "useVersionDelta" : false,
  "verifyConfig" : false,
  "nyxMode" : false,
  "debug" : false,
  "useExampleTestPlan" : false,
  "startUpClusterForDebugging" : false,
  "useFixedCommand" : false,
  "prepareImageFirst" : true,
  "enable_fsimage" : true
}
JSON
            ;;
        hbase)
            cat > "${path}" <<JSON
{
  "originalVersion" : "${original}",
  "upgradedVersion" : "${upgraded}",
  "system" : "hbase",
  "depSystem" : "hadoop",
  "depVersion" : "hadoop-2.10.2",
  "serverPort" : ${server_port},
  "clientPort" : ${client_port},
  "configDir" : "configtests",
  "STACKED_TESTS_NUM" : 1,
  "STACKED_TESTS_NUM_G2" : 1,
  "DROP_TEST_PROB_G2" : 0.1,
  "sequenceMutationEpoch" : 80,
  "nodeNum" : ${node_num},
  "faultMaxNum" : 0,
  "loadInitCorpus" : false,
  "saveCorpusToDisk" : true,
  "testSingleVersion" : false,
  "testingMode" : ${TESTING_MODE},
  "differentialExecution" : ${diff_json},
  "useTrace" : ${trace_json},
  "printTrace" : false,
  "useJaccardSimilarity" : ${jaccard_json},
  "jaccardSimilarityThreshold" : 0.3,
  "useBranchCoverage" : ${branch_json},
  "enableLogCheck" : ${logcheck_json},
  "useFormatCoverage" : false,
  "useVersionDelta" : false,
  "verifyConfig" : false,
  "nyxMode" : false,
  "debug" : false,
  "useFixedCommand" : false,
  "enableHBaseReadResultComparison" : true,
  "enable_IS_DISABLED" : true
}
JSON
            ;;
        *)
            die "Unsupported system: ${system}"
            ;;
    esac
}

collect_new_failures() {
    local before_list="$1"
    local after_list="$2"
    local out_list="$3"

    if [[ ! -f "${before_list}" ]]; then
        : > "${before_list}"
    fi
    if [[ ! -f "${after_list}" ]]; then
        : > "${after_list}"
    fi

    comm -13 "${before_list}" "${after_list}" > "${out_list}" || true
}

while [[ $# -gt 0 ]]; do
    case "$1" in
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
        --rounds)
            TARGET_ROUNDS="$2"
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
        --cassandra-retry-timeout)
            CASSANDRA_RETRY_TIMEOUT="$2"
            shift 2
            ;;
        --use-trace)
            USE_TRACE="$2"
            shift 2
            ;;
        --use-jaccard)
            USE_JACCARD="$2"
            shift 2
            ;;
        --use-branch-coverage)
            USE_BRANCH_COVERAGE="$2"
            shift 2
            ;;
        --enable-log-check)
            ENABLE_LOG_CHECK="$2"
            shift 2
            ;;
        --require-trace-signal)
            REQUIRE_TRACE_SIGNAL=true
            shift 1
            ;;
        --server-port)
            SERVER_PORT="$2"
            shift 2
            ;;
        --client-port)
            CLIENT_PORT="$2"
            shift 2
            ;;
        --node-num)
            NODE_NUM="$2"
            shift 2
            ;;
        --run-name)
            RUN_NAME="$2"
            shift 2
            ;;
        --skip-pre-clean)
            SKIP_PRE_CLEAN=true
            shift 1
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
require_cmd rg
require_cmd ss

case "${SYSTEM}" in
    cassandra)
        [[ "${ORIGINAL_VERSION}" == apache-cassandra-* ]] || die "Cassandra original version must start with apache-cassandra-"
        [[ "${UPGRADED_VERSION}" == apache-cassandra-* ]] || die "Cassandra upgraded version must start with apache-cassandra-"
        [[ -n "${NODE_NUM}" ]] || NODE_NUM=2
        ;;
    hdfs)
        [[ "${ORIGINAL_VERSION}" == hadoop-* ]] || die "HDFS original version must start with hadoop-"
        [[ "${UPGRADED_VERSION}" == hadoop-* ]] || die "HDFS upgraded version must start with hadoop-"
        [[ -n "${NODE_NUM}" ]] || NODE_NUM=3
        ;;
    hbase)
        [[ "${ORIGINAL_VERSION}" == hbase-* ]] || die "HBase original version must start with hbase-"
        [[ "${UPGRADED_VERSION}" == hbase-* ]] || die "HBase upgraded version must start with hbase-"
        [[ -n "${NODE_NUM}" ]] || NODE_NUM=2
        ;;
    *)
        die "Unsupported system: ${SYSTEM}"
        ;;
esac

if [[ "${SERVER_PORT}" -eq "${CLIENT_PORT}" ]]; then
    CLIENT_PORT=$((CLIENT_PORT + 1))
fi

SERVER_PORT="$(pick_free_port_from "${SERVER_PORT}")"
if [[ "${CLIENT_PORT}" -eq "${SERVER_PORT}" ]]; then
    CLIENT_PORT=$((CLIENT_PORT + 1))
fi
CLIENT_PORT="$(pick_free_port_from "${CLIENT_PORT}")"
if [[ "${CLIENT_PORT}" -eq "${SERVER_PORT}" ]]; then
    CLIENT_PORT="$(pick_free_port_from "$((CLIENT_PORT + 1))")"
fi

IMAGE_TAG="upfuzz_${SYSTEM}:${ORIGINAL_VERSION}_${UPGRADED_VERSION}"
if ! docker image inspect "${IMAGE_TAG}" >/dev/null 2>&1; then
    die "Docker image not found: ${IMAGE_TAG}"
fi

if [[ "${SYSTEM}" == "hbase" ]]; then
    if ! docker image inspect "upfuzz_hdfs:hadoop-2.10.2" >/dev/null 2>&1; then
        die "Required dependency image for HBase is missing: upfuzz_hdfs:hadoop-2.10.2"
    fi
fi

if [[ "${USE_TRACE}" == true && "${SYSTEM}" == "cassandra" && "${NODE_NUM}" -lt 2 ]]; then
    log "WARNING: Cassandra use-trace with node-num=${NODE_NUM} can miss inter-node traffic. Prefer --node-num 2+ for trace verification."
fi

RUN_TS="$(date '+%Y%m%d_%H%M%S')"
if [[ -z "${RUN_NAME}" ]]; then
    RUN_NAME="${SYSTEM}_${ORIGINAL_VERSION}_to_${UPGRADED_VERSION}_${RUN_TS}"
fi
RUN_NAME="$(sanitize_name "${RUN_NAME}")"
RUN_DIR="${RESULTS_ROOT}/${RUN_NAME}"
mkdir -p "${RUN_DIR}"

CONFIG_PATH="${RUN_DIR}/config.json"
MONITOR_LOG="${RUN_DIR}/monitor.log"
SUMMARY_PATH="${RUN_DIR}/summary.txt"
METADATA_PATH="${RUN_DIR}/metadata.env"

SERVER_STDOUT="${RUN_DIR}/server_stdout.log"
CLIENT_LAUNCHER_STDOUT="${RUN_DIR}/client_launcher_stdout.log"
PRE_CLEAN_LOG="${RUN_DIR}/pre_clean.log"
POST_CLEAN_LOG="${RUN_DIR}/post_clean.log"

SERVER_LOG="${ROOT_DIR}/logs/upfuzz_server.log"
CLIENT_LOG_PREFIX="${ROOT_DIR}/logs/upfuzz_client"

log "Run directory: ${RUN_DIR}"
log "Using image: ${IMAGE_TAG}"
log "Config will be generated at: ${CONFIG_PATH}"

write_config_json "${CONFIG_PATH}" "${SYSTEM}" "${ORIGINAL_VERSION}" "${UPGRADED_VERSION}" "${NODE_NUM}" "${SERVER_PORT}" "${CLIENT_PORT}"

cat > "${METADATA_PATH}" <<META
SYSTEM=${SYSTEM}
ORIGINAL_VERSION=${ORIGINAL_VERSION}
UPGRADED_VERSION=${UPGRADED_VERSION}
IMAGE_TAG=${IMAGE_TAG}
TARGET_ROUNDS=${TARGET_ROUNDS}
TIMEOUT_SEC=${TIMEOUT_SEC}
CLIENTS=${CLIENTS}
TESTING_MODE=${TESTING_MODE}
DIFFERENTIAL_EXECUTION=${USE_DIFF}
CASSANDRA_RETRY_TIMEOUT=${CASSANDRA_RETRY_TIMEOUT}
USE_TRACE=${USE_TRACE}
USE_JACCARD=${USE_JACCARD}
USE_BRANCH_COVERAGE=${USE_BRANCH_COVERAGE}
ENABLE_LOG_CHECK=${ENABLE_LOG_CHECK}
SERVER_PORT=${SERVER_PORT}
CLIENT_PORT=${CLIENT_PORT}
NODE_NUM=${NODE_NUM}
RUN_NAME=${RUN_NAME}
RUN_DIR=${RUN_DIR}
REQUIRE_TRACE_SIGNAL=${REQUIRE_TRACE_SIGNAL}
META

if [[ "${SKIP_PRE_CLEAN}" == false ]]; then
    log "Running pre-clean to remove old upfuzz processes/containers"
    (
        cd "${ROOT_DIR}"
        bin/clean.sh --force
    ) > "${PRE_CLEAN_LOG}" 2>&1 || true
fi

mkdir -p "${ROOT_DIR}/logs"
: > "${SERVER_LOG}"
for i in $(seq 1 "${CLIENTS}"); do
    : > "${CLIENT_LOG_PREFIX}_${i}.log"
done

mkdir -p "${ROOT_DIR}/failure"
find "${ROOT_DIR}/failure" -maxdepth 1 -type d -name 'failure_*' -printf '%f\n' | sort > "${RUN_DIR}/failure_before.txt" || true

START_TS="$(date '+%F %T')"
START_EPOCH="$(date +%s)"

log "Launching server"
(
    cd "${ROOT_DIR}"
    bin/start_server.sh "${CONFIG_PATH}" > "${SERVER_STDOUT}" 2>&1
) &
SERVER_PID=$!

echo "${SERVER_PID}" > "${RUN_DIR}/server_pid.txt"

sleep 3

if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
    log "Server exited early; capturing artifacts"
fi

log "Launching ${CLIENTS} client(s)"
(
    cd "${ROOT_DIR}"
    bin/start_clients.sh "${CLIENTS}" "${CONFIG_PATH}" > "${CLIENT_LAUNCHER_STDOUT}" 2>&1
) &
CLIENT_LAUNCHER_PID=$!
wait "${CLIENT_LAUNCHER_PID}" || true

echo "${CLIENT_LAUNCHER_PID}" > "${RUN_DIR}/client_launcher_pid.txt"

stop_reason=""
last_rounds=0
target_rounds_first_seen_epoch=""
TARGET_ROUND_GRACE_SEC=120

{
    echo "timestamp,elapsed_sec,rounds,diff_feedback_packets,server_alive"
} > "${MONITOR_LOG}"

log "Monitoring execution until ${TARGET_ROUNDS} rounds complete (or timeout ${TIMEOUT_SEC}s)"
while true; do
    now_epoch="$(date +%s)"
    elapsed=$((now_epoch - START_EPOCH))

    rounds="$(extract_total_exec "${SERVER_LOG}")"
    diff_feedback_packets="$(count_diff_feedback_packets "${SERVER_LOG}")"

    server_alive=1
    if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
        server_alive=0
    fi

    echo "$(date '+%F %T'),${elapsed},${rounds},${diff_feedback_packets},${server_alive}" >> "${MONITOR_LOG}"

    if (( rounds > last_rounds )); then
        log "Observed progress: total_exec=${rounds}, diff_feedback_packets=${diff_feedback_packets}"
        last_rounds="${rounds}"
    fi

    if (( rounds >= TARGET_ROUNDS )); then
        if [[ "${USE_TRACE}" == true ]]; then
            tri_diff_count="$(safe_rg_count_file 'Message identity tri-diff:' "${SERVER_LOG}")"
            if (( tri_diff_count < TARGET_ROUNDS )); then
                if [[ -z "${target_rounds_first_seen_epoch}" ]]; then
                    target_rounds_first_seen_epoch="${now_epoch}"
                    log "Target rounds reached; waiting for tri-diff logs (${tri_diff_count}/${TARGET_ROUNDS})"
                fi
                grace_elapsed=$((now_epoch - target_rounds_first_seen_epoch))
                if (( grace_elapsed < TARGET_ROUND_GRACE_SEC )); then
                    sleep 10
                    continue
                fi
                log "Tri-diff logs still incomplete after ${TARGET_ROUND_GRACE_SEC}s grace (${tri_diff_count}/${TARGET_ROUNDS}); stopping anyway"
            fi
        fi
        stop_reason="target_rounds_reached"
        break
    fi

    if (( elapsed >= TIMEOUT_SEC )); then
        stop_reason="timeout"
        break
    fi

    if (( server_alive == 0 )); then
        stop_reason="server_exited_early"
        break
    fi

    sleep 10
done

log "Stopping run (reason=${stop_reason})"

(
    cd "${ROOT_DIR}"
    docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
) > "${RUN_DIR}/docker_ps_before_cleanup.txt" 2>&1 || true

(
    cd "${ROOT_DIR}"
    bin/clean.sh --force
) > "${POST_CLEAN_LOG}" 2>&1 || true

END_TS="$(date '+%F %T')"
END_EPOCH="$(date +%s)"
DURATION_SEC=$((END_EPOCH - START_EPOCH))

final_rounds="$(extract_total_exec "${SERVER_LOG}")"
final_diff_packets="$(count_diff_feedback_packets "${SERVER_LOG}")"

cp -f "${SERVER_LOG}" "${RUN_DIR}/upfuzz_server.log" || true
for i in $(seq 1 "${CLIENTS}"); do
    cp -f "${CLIENT_LOG_PREFIX}_${i}.log" "${RUN_DIR}/upfuzz_client_${i}.log" || true
done

rg -n 'TestPlanDiffFeedbackPacket received|Jaccard Similarity|Low Jaccard similarity|Only Old|Rolling|Only New|total exec :' "${SERVER_LOG}" > "${RUN_DIR}/server_key_markers.log" 2>/dev/null || true
rg -n 'executeTestPlanPacketDifferential|trace diff: all three packets are collected|Feedback packet is null|ExecutionException|Exception' "${CLIENT_LOG_PREFIX}_1.log" > "${RUN_DIR}/client_key_markers.log" 2>/dev/null || true

find "${ROOT_DIR}/failure" -maxdepth 1 -type d -name 'failure_*' -printf '%f\n' | sort > "${RUN_DIR}/failure_after.txt" || true
collect_new_failures "${RUN_DIR}/failure_before.txt" "${RUN_DIR}/failure_after.txt" "${RUN_DIR}/failure_new_dirs.txt"

mkdir -p "${RUN_DIR}/failure_new"
while IFS= read -r dir_name; do
    [[ -z "${dir_name}" ]] && continue
    if [[ -d "${ROOT_DIR}/failure/${dir_name}" ]]; then
        cp -a "${ROOT_DIR}/failure/${dir_name}" "${RUN_DIR}/failure_new/"
    fi
done < "${RUN_DIR}/failure_new_dirs.txt"

new_failure_count="$(wc -l < "${RUN_DIR}/failure_new_dirs.txt" | tr -d ' ')"

TRACE_CONNECT_REFUSED_COUNT=0
TRACE_RECEIVED_COUNT=0
TRACE_LEN_POSITIVE_COUNT=0
TRACE_LEN_ZERO_COUNT=0
TRACE_SIGNAL_OK="n/a"

if [[ "${USE_TRACE}" == true ]]; then
    TRACE_CONNECT_REFUSED_COUNT="$(count_pattern_in_clients 'Connection refused')"
    TRACE_RECEIVED_COUNT="$(count_pattern_in_clients 'Received trace = ')"
    TRACE_LEN_POSITIVE_COUNT="$(safe_rg_count_file 'trace\[[0-9]+\] len = [1-9][0-9]*' "${SERVER_LOG}")"
    TRACE_LEN_ZERO_COUNT="$(safe_rg_count_file 'trace\[[0-9]+\] len = 0' "${SERVER_LOG}")"
    if (( TRACE_RECEIVED_COUNT > 0 || TRACE_LEN_POSITIVE_COUNT > 0 )); then
        TRACE_SIGNAL_OK=true
    else
        TRACE_SIGNAL_OK=false
    fi
fi

cat > "${SUMMARY_PATH}" <<SUMMARY
run_name: ${RUN_NAME}
system: ${SYSTEM}
original_version: ${ORIGINAL_VERSION}
upgraded_version: ${UPGRADED_VERSION}
image_tag: ${IMAGE_TAG}
config_path: ${CONFIG_PATH}
start_time: ${START_TS}
end_time: ${END_TS}
duration_sec: ${DURATION_SEC}
target_rounds: ${TARGET_ROUNDS}
observed_rounds: ${final_rounds}
diff_feedback_packets: ${final_diff_packets}
stop_reason: ${stop_reason}
clients: ${CLIENTS}
node_num: ${NODE_NUM}
testing_mode: ${TESTING_MODE}
differential_execution: ${USE_DIFF}
server_port: ${SERVER_PORT}
client_port: ${CLIENT_PORT}
new_failure_dirs: ${new_failure_count}
trace_enabled: ${USE_TRACE}
trace_signal_ok: ${TRACE_SIGNAL_OK}
trace_connect_refused_count: ${TRACE_CONNECT_REFUSED_COUNT}
trace_received_count: ${TRACE_RECEIVED_COUNT}
trace_len_positive_count: ${TRACE_LEN_POSITIVE_COUNT}
trace_len_zero_count: ${TRACE_LEN_ZERO_COUNT}
require_trace_signal: ${REQUIRE_TRACE_SIGNAL}
server_stdout: ${SERVER_STDOUT}
client_launcher_stdout: ${CLIENT_LAUNCHER_STDOUT}
server_log_copy: ${RUN_DIR}/upfuzz_server.log
client_log_copy: ${RUN_DIR}/upfuzz_client_1.log
SUMMARY

log "Run completed. Summary: ${SUMMARY_PATH}"

if (( final_rounds < TARGET_ROUNDS )); then
    echo "WARNING: Target rounds not reached (observed=${final_rounds}, target=${TARGET_ROUNDS})" >&2
    exit 2
fi

if [[ "${USE_TRACE}" == true && "${REQUIRE_TRACE_SIGNAL}" == true ]]; then
    if [[ "${TRACE_SIGNAL_OK}" != true ]]; then
        echo "ERROR: Trace signal missing. Check node count, instrumentation, and runtime logs." >&2
        exit 3
    fi
    if (( TRACE_CONNECT_REFUSED_COUNT > 0 )); then
        echo "ERROR: Trace collection observed Connection refused (${TRACE_CONNECT_REFUSED_COUNT} times)." >&2
        exit 4
    fi
fi

exit 0
