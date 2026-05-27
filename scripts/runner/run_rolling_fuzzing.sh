#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RESULTS_ROOT="${SCRIPT_DIR}/results"
mkdir -p "${RESULTS_ROOT}"

GIT_SHA="$(cd "${ROOT_DIR}" && git rev-parse --short HEAD 2>/dev/null || echo 'unknown')"

SYSTEM="cassandra"
ORIGINAL_VERSION="apache-cassandra-4.1.10"
UPGRADED_VERSION="apache-cassandra-5.0.6"
TARGET_ROUNDS=2
TIMEOUT_SEC=3600
CLIENTS=1
TESTING_MODE=3
USE_DIFF=true
USE_TRACE=true
PRINT_TRACE=false
USE_CANONICAL_TRACE=true
CANONICAL_WINDOW_SIM_THRESHOLD=0.75
CANONICAL_AGGREGATE_SIM_THRESHOLD=0.85
CANONICAL_WINDOW_DIVERGENCE_MARGIN=0.08
CANONICAL_AGGREGATE_DIVERGENCE_MARGIN=0.05
CANONICAL_MIN_WINDOW_EVENTS=5
USE_CANONICAL_MESSAGE_IDENTITY=true
ROLLING_EXCLUSIVE_MIN_COUNT=3
ROLLING_MISSING_MIN_COUNT=3
ROLLING_EXCLUSIVE_FRACTION_THRESHOLD=0.05
ROLLING_MISSING_FRACTION_THRESHOLD=0.05
USE_BRANCH_COVERAGE=true
ENABLE_LOG_CHECK=true
REQUIRE_TRACE_SIGNAL=false
CASSANDRA_RETRY_TIMEOUT=300
DIFF_LANE_TIMEOUT_SEC=1200
ENABLE_CHECKPOINT_RESTORE=false
CHECKPOINT_SELECTED_NODES="0"
CHECKPOINT_ALL_LANES=true
CHECKPOINT_CACHE_DIR="fuzzing_storage/checkpoints"
CHECKPOINT_REUSE=false
CHECKPOINT_ALLOW_NON_CASSANDRA=false
CHECKPOINT_WORKLOAD_ONLY_BENCHMARK=false
HBASE_DAEMON_RETRY_TIMES=40
NODE_NUM=""
FIXED_CONFIG_IDX=-1
SERVER_PORT=7399
CLIENT_PORT=7400
SERVER_START_TIMEOUT_SEC=120
RUN_NAME=""
SKIP_PRE_CLEAN=false
# Phase 5 system-level preset + version-aware classifier profile.
# Default AUTO resolves on the Java side from the JSON `system` field.
TRACE_SYSTEM_PRESET="AUTO"
# Optional path to a version-aware family-map profile produced by
# nettrace-shuai/rupfuzz-nettrace/scripts/generate_family_inventories.sh.
# Empty string means "no override"; the runner auto-derives a default
# from the prebuild family-maps tree if a matching file exists.
FAMILY_MAP_PROFILE=""
DEFAULT_FAMILY_MAP_DIR="${ROOT_DIR}/../nettrace-shuai/rupfuzz-nettrace/profiles/family-maps"
# Phase-5 raw classifier-input dump. When true, the server emits
# trace_window_classifier_inputs.csv alongside the other observability
# CSVs so the TraceReplay metadata tier can re-classify raw tuples
# under a different version-aware family-map profile. Off by default —
# enable explicitly when staging a profile change.
ENABLE_FLOW_TUPLE_DUMP="false"
FLOW_TUPLE_DUMP_TOP_K=50

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
  --testing-mode <3|5|6>                 3=example testplan, 5=rolling-only, 6=rolling-only branch-only (default: ${TESTING_MODE})
  --diff-lane-timeout-sec <sec>          Differential lane timeout for all systems (default: ${DIFF_LANE_TIMEOUT_SEC})
  --enable-checkpoint-restore <true|false> Enable mode-5 Docker checkpoint startup path (default: ${ENABLE_CHECKPOINT_RESTORE})
  --checkpoint-selected-nodes <csv>      Node indexes for checkpoint prefix, e.g. 0 or 0,1 (default: ${CHECKPOINT_SELECTED_NODES})
  --checkpoint-all-lanes <true|false>    Apply checkpoint prefix to old-old, rolling, and new-new lanes (default: ${CHECKPOINT_ALL_LANES})
  --checkpoint-cache-dir <path>          Checkpoint artifact directory for Java config (default: ${CHECKPOINT_CACHE_DIR})
  --checkpoint-reuse <true|false>        Reserve persistent checkpoint reuse knob in config (default: ${CHECKPOINT_REUSE})
  --checkpoint-allow-non-cassandra <true|false> Allow checkpoint mode for HDFS/HBase after validation (default: ${CHECKPOINT_ALLOW_NON_CASSANDRA})
  --checkpoint-workload-only-benchmark <true|false> Deprecated compatibility knob; checkpoint restore always executes only workload events after restore (default: ${CHECKPOINT_WORKLOAD_ONLY_BENCHMARK})
  --cassandra-retry-timeout <sec>        Cassandra cqlsh retry timeout (default: ${CASSANDRA_RETRY_TIMEOUT})
  --hbase-daemon-retry-times <N>         HBase shell daemon retry attempts (default: ${HBASE_DAEMON_RETRY_TIMES})
  --use-trace <true|false>               Enable network trace collection (default: ${USE_TRACE})
  --print-trace <true|false>             Print detailed trace entries in server log (default: ${PRINT_TRACE})
  --use-canonical-trace <true|false>     Enable canonical trace similarity (default: ${USE_CANONICAL_TRACE})
  --canonical-window-sim-threshold <N>   Window-level similarity threshold (default: ${CANONICAL_WINDOW_SIM_THRESHOLD})
  --canonical-aggregate-sim-threshold <N> Aggregate similarity threshold (default: ${CANONICAL_AGGREGATE_SIM_THRESHOLD})
  --canonical-window-divergence-margin <N> Window divergence margin (default: ${CANONICAL_WINDOW_DIVERGENCE_MARGIN})
  --canonical-aggregate-divergence-margin <N> Aggregate divergence margin (default: ${CANONICAL_AGGREGATE_DIVERGENCE_MARGIN})
  --canonical-min-window-events <N>      Min events per window (default: ${CANONICAL_MIN_WINDOW_EVENTS})
  --use-canonical-message-identity <true|false> Enable canonical tri-diff (default: ${USE_CANONICAL_MESSAGE_IDENTITY})
  --rolling-exclusive-min-count <N>      Tri-diff exclusive min count (default: ${ROLLING_EXCLUSIVE_MIN_COUNT})
  --rolling-missing-min-count <N>        Tri-diff missing min count (default: ${ROLLING_MISSING_MIN_COUNT})
  --rolling-exclusive-fraction-threshold <N> Tri-diff exclusive fraction (default: ${ROLLING_EXCLUSIVE_FRACTION_THRESHOLD})
  --rolling-missing-fraction-threshold <N> Tri-diff missing fraction (default: ${ROLLING_MISSING_FRACTION_THRESHOLD})
  --use-branch-coverage <true|false>     Enable branch coverage signals (default: ${USE_BRANCH_COVERAGE})
  --enable-log-check <true|false>        Enable error-log oracle (default: ${ENABLE_LOG_CHECK})
  --require-trace-signal                 Fail if trace signal is missing when --use-trace=true
  --server-port <port>                   Server port (default: ${SERVER_PORT}, auto-shift if busy)
  --client-port <port>                   Client port (default: ${CLIENT_PORT}, auto-shift if busy)
  --server-start-timeout-sec <N>         Max wait for server port listen before client launch (default: ${SERVER_START_TIMEOUT_SEC})
  --node-num <N>                         Override node number (default by system: cass=2,hdfs=3,hbase=3)
  --fixed-config-idx <N>                 Force example-testplan config index test<N> (default: random)
  --run-name <name>                      Result folder name (default: auto generated)
  --skip-pre-clean                       Skip pre-run clean.sh
  --trace-system-preset <name>           Phase 5 system preset (default: ${TRACE_SYSTEM_PRESET}). Values: AUTO|GENERIC|CASSANDRA|HDFS|HBASE
  --family-map-profile <path>            Phase 5 long-tail family-map profile YAML (default: auto-derived from prebuild family-maps if present)
  --enable-flow-tuple-dump <true|false>  Emit trace_window_classifier_inputs.csv for TraceReplay metadata tier (default: ${ENABLE_FLOW_TUPLE_DUMP})
  --flow-tuple-dump-top-k <N>            Cap per-(round,window,lane) tuples emitted when dump is enabled; 0 = unbounded (default: ${FLOW_TUPLE_DUMP_TOP_K})
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

json_int_array_from_csv() {
    local csv="$1"
    local out="["
    local sep=""
    local item
    IFS=',' read -r -a _items <<< "${csv}"
    for item in "${_items[@]}"; do
        item="$(echo "${item}" | tr -d '[:space:]')"
        [[ "${item}" =~ ^[0-9]+$ ]] || die "checkpoint node indexes must be non-negative integers (got: ${item})"
        out+="${sep}${item}"
        sep=","
    done
    [[ "${out}" != "[" ]] || die "--checkpoint-selected-nodes cannot be empty"
    out+="]"
    echo "${out}"
}

is_port_in_use() {
    local port="$1"
    ss -ltn 2>/dev/null | awk '{print $4}' | rg -q "(^|[:.])${port}$"
}

cleanup_upfuzz_networks() {
    local nets
    nets="$(docker network ls --format '{{.Name}}' | rg '_network_(cassandra|hdfs|hbase|ozone)_' || true)"
    if [[ -n "${nets}" ]]; then
        echo "${nets}" | xargs -r docker network rm >/dev/null 2>&1 || true
    fi
}

wait_for_server_listen() {
    local server_pid="$1"
    local server_port="$2"
    local timeout_sec="$3"
    local waited=0
    while (( waited < timeout_sec )); do
        if ! kill -0 "${server_pid}" 2>/dev/null; then
            return 1
        fi
        if is_port_in_use "${server_port}"; then
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 2
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

sanitize_hbase_cached_classpath_for_version() {
    local version="$1"
    local target_dir="${ROOT_DIR}/prebuild/hbase/${version}/hbase-build-configuration/target"
    local ruby_dir="${ROOT_DIR}/prebuild/hbase/${version}/lib/ruby"

    if [[ ! -d "${target_dir}" || ! -d "${ruby_dir}" ]]; then
        return
    fi

    local jruby_jar
    jruby_jar="$(ls "${ruby_dir}"/jruby-complete-*.jar 2>/dev/null | head -n1 || true)"
    if [[ -z "${jruby_jar}" ]]; then
        return
    fi

    local jruby_name
    jruby_name="$(basename "${jruby_jar}")"

    # Avoid host-specific absolute paths copied from another machine.
    # Keep only in-container paths so HBase shell daemon can always start.
    : > "${target_dir}/cached_classpath.txt"
    : > "${target_dir}/cached_classpath_jline.txt"
    printf '/hbase/%s/lib/ruby/%s\n' "${version}" "${jruby_name}" > "${target_dir}/cached_classpath_jruby.txt"
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

# Phase 3-4 trace-agnostic config knobs materialized explicitly so the
# run artifact records which policy was active. Values match Java-side
# defaults in Config.Configuration.
#
# Phase 5 RETIRED the per-system trace scoring/routing knobs from this
# block — they are now controlled by the system-level
# {@link org.zlab.upfuzz.fuzzingengine.TraceSystemPreset} preset applied
# in Config.setInstance. The runner emits `traceSystemPreset` instead;
# the preset overrides the following retired knobs:
#   traceStrongScoreThreshold / traceWeakScoreThreshold,
#   traceBoundaryBonus / traceOrderBonusCap / traceBackgroundCap,
#   traceUpgradeCriticalFamilyWeight / traceBackgroundFamilyWeight /
#   traceUnknownFamilyWeight,
#   traceMinBaselineAgreementForStrong / traceMinRollingDivergenceForStrong,
#   mainExploitQueueWeight / branchScoutQueueWeight /
#   shadowEvalQueueWeight / reproConfirmQueueWeight,
#   traceOnlyAdmissionCapPerRound / traceOnlyAdmissionCapPer100Rounds,
#   enableLowerConfidenceTraceAdmission.
# Override individual knobs by setting `traceSystemPreset: "GENERIC"` in
# the JSON and emitting them yourself.
PHASE_25_CONFIG='  "usePriorityTestPlanScheduler" : true,
  "mainExploitMutationEpoch" : 30,
  "branchScoutMutationEpoch" : 10,
  "shadowEvalMutationEpoch" : 4,
  "reproConfirmMutationEpoch" : 50,
  "mainExploitQueueMaxSize" : 256,
  "branchScoutQueueMaxSize" : 256,
  "shadowEvalQueueMaxSize" : 128,
  "reproConfirmQueueMaxSize" : 64,
  "enableTestPlanCompactDedup" : true,
  "testPlanDequeueDecayThreshold" : 3,
  "enableStageFocusedMutation" : true,
  "stageAwareMutationProbability" : 0.65,
  "stageAwareMutationMaxAttempts" : 3,
  "enableStageTemplates" : true,
  "stageTemplateProbability" : 0.25,
  "preUpgradeOnlyDownrank" : true,
  "preUpgradeOnlyMutationEpochCap" : 6,
  "strongCandidateConfirmationBudget" : 6,
  "weakCandidateConfirmationBudget" : 2,
  "enableCoverageGuidance" : true,
  "rollingPostUpgradeScoreBoost" : 3.0,
  "rollingPreUpgradeScoreBoost" : 0.5,
  "sharedNoveltyScoreBoost" : 1.0,
  "baselineOnlyScoreBoost" : 0.5,
  "branchScoutMinOccupancy" : 5,
  "enableStageCoverageSnapshots" : false'

# Phase 5 system preset + family-map profile JSON fragment, materialized
# per-run so each generated config records which preset / profile / dump
# state was active. The Java side resolves AUTO from the JSON `system`
# field. `traceFlowTupleDumpTopK` is only emitted when the dump is on —
# with the dump off the default 50 is irrelevant and keeps the config
# output tight.
build_phase5_block() {
    local preset="$1"
    local profile_path="$2"
    local dump_enabled="$3"
    local dump_top_k="$4"
    printf '  "traceSystemPreset" : "%s"' "${preset}"
    if [[ -n "${profile_path}" ]]; then
        printf ',\n  "familyMapProfilePath" : "%s"' "${profile_path}"
    fi
    printf ',\n  "enableTraceFlowTupleDump" : %s' "${dump_enabled}"
    if [[ "${dump_enabled}" == "true" ]]; then
        printf ',\n  "traceFlowTupleDumpTopK" : %d' "${dump_top_k}"
    fi
}

# Auto-derive a family-map profile from the prebuild family-maps tree
# when the user does not specify one explicitly. Returns empty when no
# matching file exists.
auto_derive_family_map() {
    local system="$1"
    local original_version="$2"
    local candidate="${DEFAULT_FAMILY_MAP_DIR}/${system}-${original_version}-family-map.yaml"
    if [[ -f "${candidate}" ]]; then
        echo "${candidate}"
    fi
}

write_config_json() {
    local path="$1"
    local system="$2"
    local original="$3"
    local upgraded="$4"
    local node_num="$5"
    local server_port="$6"
    local client_port="$7"
    local fixed_config_idx="$8"

    local diff_json
    local trace_json
    local print_trace_json
    local branch_json
    local logcheck_json
    local checkpoint_restore_json
    local checkpoint_all_lanes_json
    local checkpoint_reuse_json
    local checkpoint_allow_non_cassandra_json
    local checkpoint_workload_only_json
    diff_json="$(bool_json "${USE_DIFF}")"
    trace_json="$(bool_json "${USE_TRACE}")"
    print_trace_json="$(bool_json "${PRINT_TRACE}")"
    canonical_trace_json="$(bool_json "${USE_CANONICAL_TRACE}")"
    canonical_msg_identity_json="$(bool_json "${USE_CANONICAL_MESSAGE_IDENTITY}")"
    branch_json="$(bool_json "${USE_BRANCH_COVERAGE}")"
    logcheck_json="$(bool_json "${ENABLE_LOG_CHECK}")"
    checkpoint_restore_json="$(bool_json "${ENABLE_CHECKPOINT_RESTORE}")"
    checkpoint_all_lanes_json="$(bool_json "${CHECKPOINT_ALL_LANES}")"
    checkpoint_reuse_json="$(bool_json "${CHECKPOINT_REUSE}")"
    checkpoint_allow_non_cassandra_json="$(bool_json "${CHECKPOINT_ALLOW_NON_CASSANDRA}")"
    checkpoint_workload_only_json="$(bool_json "${CHECKPOINT_WORKLOAD_ONLY_BENCHMARK}")"

    local phase5_block
    phase5_block="$(build_phase5_block \
        "${TRACE_SYSTEM_PRESET}" \
        "${FAMILY_MAP_PROFILE}" \
        "${ENABLE_FLOW_TUPLE_DUMP}" \
        "${FLOW_TUPLE_DUMP_TOP_K}")"

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
  "enableCheckpointRestore" : ${checkpoint_restore_json},
  "checkpointSelectedNodes" : ${CHECKPOINT_SELECTED_NODES_JSON},
  "checkpointAllLanes" : ${checkpoint_all_lanes_json},
  "checkpointCacheDir" : "${CHECKPOINT_CACHE_DIR}",
  "checkpointReuse" : ${checkpoint_reuse_json},
  "checkpointAllowNonCassandra" : ${checkpoint_allow_non_cassandra_json},
  "checkpointWorkloadOnlyBenchmark" : ${checkpoint_workload_only_json},
  "useTrace" : ${trace_json},
  "printTrace" : ${print_trace_json},
  "useCanonicalTraceSimilarity" : ${canonical_trace_json},
  "canonicalRollingMinWindowSimilarityThreshold" : ${CANONICAL_WINDOW_SIM_THRESHOLD},
  "canonicalWindowDivergenceMarginThreshold" : ${CANONICAL_WINDOW_DIVERGENCE_MARGIN},
  "canonicalMinWindowEventCount" : ${CANONICAL_MIN_WINDOW_EVENTS},
  "canonicalRollingMinAggregateSimilarityThreshold" : ${CANONICAL_AGGREGATE_SIM_THRESHOLD},
  "canonicalAggregateDivergenceMarginThreshold" : ${CANONICAL_AGGREGATE_DIVERGENCE_MARGIN},
  "useCanonicalMessageIdentityDiff" : ${canonical_msg_identity_json},
  "rollingExclusiveMinCount" : ${ROLLING_EXCLUSIVE_MIN_COUNT},
  "rollingMissingMinCount" : ${ROLLING_MISSING_MIN_COUNT},
  "rollingExclusiveFractionThreshold" : ${ROLLING_EXCLUSIVE_FRACTION_THRESHOLD},
  "rollingMissingFractionThreshold" : ${ROLLING_MISSING_FRACTION_THRESHOLD},
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
  "fixedConfigIdx" : ${fixed_config_idx},
  "startUpClusterForDebugging" : false,
  "drain" : false,
  "useFixedCommand" : false,
  "enable_ORDERBY_IN_SELECT" : true,
  "cassandraEnableTimeoutCheck" : false,
  "differentialLaneTimeoutSec" : ${DIFF_LANE_TIMEOUT_SEC},
  "CASSANDRA_RETRY_TIMEOUT" : ${CASSANDRA_RETRY_TIMEOUT},
${PHASE_25_CONFIG},
${phase5_block}
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
  "enableCheckpointRestore" : ${checkpoint_restore_json},
  "checkpointSelectedNodes" : ${CHECKPOINT_SELECTED_NODES_JSON},
  "checkpointAllLanes" : ${checkpoint_all_lanes_json},
  "checkpointCacheDir" : "${CHECKPOINT_CACHE_DIR}",
  "checkpointReuse" : ${checkpoint_reuse_json},
  "checkpointAllowNonCassandra" : ${checkpoint_allow_non_cassandra_json},
  "checkpointWorkloadOnlyBenchmark" : ${checkpoint_workload_only_json},
  "useTrace" : ${trace_json},
  "printTrace" : ${print_trace_json},
  "useCanonicalTraceSimilarity" : ${canonical_trace_json},
  "canonicalRollingMinWindowSimilarityThreshold" : ${CANONICAL_WINDOW_SIM_THRESHOLD},
  "canonicalWindowDivergenceMarginThreshold" : ${CANONICAL_WINDOW_DIVERGENCE_MARGIN},
  "canonicalMinWindowEventCount" : ${CANONICAL_MIN_WINDOW_EVENTS},
  "canonicalRollingMinAggregateSimilarityThreshold" : ${CANONICAL_AGGREGATE_SIM_THRESHOLD},
  "canonicalAggregateDivergenceMarginThreshold" : ${CANONICAL_AGGREGATE_DIVERGENCE_MARGIN},
  "useCanonicalMessageIdentityDiff" : ${canonical_msg_identity_json},
  "rollingExclusiveMinCount" : ${ROLLING_EXCLUSIVE_MIN_COUNT},
  "rollingMissingMinCount" : ${ROLLING_MISSING_MIN_COUNT},
  "rollingExclusiveFractionThreshold" : ${ROLLING_EXCLUSIVE_FRACTION_THRESHOLD},
  "rollingMissingFractionThreshold" : ${ROLLING_MISSING_FRACTION_THRESHOLD},
  "useBranchCoverage" : ${branch_json},
  "enableLogCheck" : ${logcheck_json},
  "useFormatCoverage" : false,
  "useVersionDelta" : false,
  "verifyConfig" : false,
  "nyxMode" : false,
  "debug" : false,
  "useExampleTestPlan" : false,
  "fixedConfigIdx" : ${fixed_config_idx},
  "startUpClusterForDebugging" : false,
  "useFixedCommand" : false,
  "prepareImageFirst" : true,
  "enable_fsimage" : true,
  "differentialLaneTimeoutSec" : ${DIFF_LANE_TIMEOUT_SEC},
${PHASE_25_CONFIG},
${phase5_block}
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
  "enableCheckpointRestore" : ${checkpoint_restore_json},
  "checkpointSelectedNodes" : ${CHECKPOINT_SELECTED_NODES_JSON},
  "checkpointAllLanes" : ${checkpoint_all_lanes_json},
  "checkpointCacheDir" : "${CHECKPOINT_CACHE_DIR}",
  "checkpointReuse" : ${checkpoint_reuse_json},
  "checkpointAllowNonCassandra" : ${checkpoint_allow_non_cassandra_json},
  "checkpointWorkloadOnlyBenchmark" : ${checkpoint_workload_only_json},
  "useTrace" : ${trace_json},
  "printTrace" : ${print_trace_json},
  "useCanonicalTraceSimilarity" : ${canonical_trace_json},
  "canonicalRollingMinWindowSimilarityThreshold" : ${CANONICAL_WINDOW_SIM_THRESHOLD},
  "canonicalWindowDivergenceMarginThreshold" : ${CANONICAL_WINDOW_DIVERGENCE_MARGIN},
  "canonicalMinWindowEventCount" : ${CANONICAL_MIN_WINDOW_EVENTS},
  "canonicalRollingMinAggregateSimilarityThreshold" : ${CANONICAL_AGGREGATE_SIM_THRESHOLD},
  "canonicalAggregateDivergenceMarginThreshold" : ${CANONICAL_AGGREGATE_DIVERGENCE_MARGIN},
  "useCanonicalMessageIdentityDiff" : ${canonical_msg_identity_json},
  "rollingExclusiveMinCount" : ${ROLLING_EXCLUSIVE_MIN_COUNT},
  "rollingMissingMinCount" : ${ROLLING_MISSING_MIN_COUNT},
  "rollingExclusiveFractionThreshold" : ${ROLLING_EXCLUSIVE_FRACTION_THRESHOLD},
  "rollingMissingFractionThreshold" : ${ROLLING_MISSING_FRACTION_THRESHOLD},
  "useBranchCoverage" : ${branch_json},
  "enableLogCheck" : ${logcheck_json},
  "useFormatCoverage" : false,
  "useVersionDelta" : false,
  "verifyConfig" : false,
  "nyxMode" : false,
  "debug" : false,
  "useFixedCommand" : false,
  "fixedConfigIdx" : ${fixed_config_idx},
  "enableHBaseReadResultComparison" : true,
  "enable_IS_DISABLED" : true,
  "hbaseDaemonRetryTimes" : ${HBASE_DAEMON_RETRY_TIMES},
  "differentialLaneTimeoutSec" : ${DIFF_LANE_TIMEOUT_SEC},
${PHASE_25_CONFIG},
${phase5_block}
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
        --diff-lane-timeout-sec)
            DIFF_LANE_TIMEOUT_SEC="$2"
            shift 2
            ;;
        --enable-checkpoint-restore)
            ENABLE_CHECKPOINT_RESTORE="$2"
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
        --checkpoint-reuse)
            CHECKPOINT_REUSE="$2"
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
        --hbase-daemon-retry-times)
            HBASE_DAEMON_RETRY_TIMES="$2"
            shift 2
            ;;
        --use-trace)
            USE_TRACE="$2"
            shift 2
            ;;
        --print-trace)
            PRINT_TRACE="$2"
            shift 2
            ;;
        --use-canonical-trace)
            USE_CANONICAL_TRACE="$2"
            shift 2
            ;;
        --canonical-window-sim-threshold)
            CANONICAL_WINDOW_SIM_THRESHOLD="$2"
            shift 2
            ;;
        --canonical-aggregate-sim-threshold)
            CANONICAL_AGGREGATE_SIM_THRESHOLD="$2"
            shift 2
            ;;
        --canonical-window-divergence-margin)
            CANONICAL_WINDOW_DIVERGENCE_MARGIN="$2"
            shift 2
            ;;
        --canonical-aggregate-divergence-margin)
            CANONICAL_AGGREGATE_DIVERGENCE_MARGIN="$2"
            shift 2
            ;;
        --canonical-min-window-events)
            CANONICAL_MIN_WINDOW_EVENTS="$2"
            shift 2
            ;;
        --use-canonical-message-identity)
            USE_CANONICAL_MESSAGE_IDENTITY="$2"
            shift 2
            ;;
        --rolling-exclusive-min-count)
            ROLLING_EXCLUSIVE_MIN_COUNT="$2"
            shift 2
            ;;
        --rolling-missing-min-count)
            ROLLING_MISSING_MIN_COUNT="$2"
            shift 2
            ;;
        --rolling-exclusive-fraction-threshold)
            ROLLING_EXCLUSIVE_FRACTION_THRESHOLD="$2"
            shift 2
            ;;
        --rolling-missing-fraction-threshold)
            ROLLING_MISSING_FRACTION_THRESHOLD="$2"
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
        --server-start-timeout-sec)
            SERVER_START_TIMEOUT_SEC="$2"
            shift 2
            ;;
        --node-num)
            NODE_NUM="$2"
            shift 2
            ;;
        --fixed-config-idx)
            FIXED_CONFIG_IDX="$2"
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
        --trace-system-preset)
            TRACE_SYSTEM_PRESET="$2"
            shift 2
            ;;
        --family-map-profile)
            FAMILY_MAP_PROFILE="$2"
            shift 2
            ;;
        --enable-flow-tuple-dump)
            ENABLE_FLOW_TUPLE_DUMP="$2"
            shift 2
            ;;
        --flow-tuple-dump-top-k)
            FLOW_TUPLE_DUMP_TOP_K="$2"
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

# Mode 6: force branch-only (no trace)
if [[ "${TESTING_MODE}" == "6" ]]; then
    USE_DIFF=true
    USE_BRANCH_COVERAGE=true
    USE_TRACE=false
    USE_CANONICAL_TRACE=false
    USE_CANONICAL_MESSAGE_IDENTITY=false
    PRINT_TRACE=false
    REQUIRE_TRACE_SIGNAL=false
fi

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
        [[ -n "${NODE_NUM}" ]] || NODE_NUM=3
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

# Validate prebuild directories exist and look correctly set up.
# These are volume-mounted into Docker containers at runtime — if they're
# missing or were extracted without the build script's config patches,
# the system won't start inside the container.
for _v in "${ORIGINAL_VERSION}" "${UPGRADED_VERSION}"; do
    _prebuild="${ROOT_DIR}/prebuild/${SYSTEM}/${_v}"
    if [[ ! -d "${_prebuild}" ]]; then
        die "Prebuild directory missing: ${_prebuild}. Run scripts/docker/build_rolling_image_pair.sh ${SYSTEM} ${ORIGINAL_VERSION} ${UPGRADED_VERSION}"
    fi
    case "${SYSTEM}" in
        cassandra)
            [[ -f "${_prebuild}/bin/cqlsh_daemon.py" ]] \
                || die "Prebuild not set up: ${_prebuild}/bin/cqlsh_daemon.py missing. Re-run build_rolling_image_pair.sh."
            ;;
        hbase)
            [[ -f "${_prebuild}/bin/hbase" ]] \
                || die "Prebuild not set up: ${_prebuild}/bin/hbase missing. Re-run build_rolling_image_pair.sh."
            ;;
        hdfs)
            [[ -f "${_prebuild}/bin/hdfs" ]] \
                || die "Prebuild not set up: ${_prebuild}/bin/hdfs missing. Re-run build_rolling_image_pair.sh."
            ;;
    esac
done

if [[ "${SYSTEM}" == "hbase" ]]; then
    if ! docker image inspect "upfuzz_hdfs:hadoop-2.10.2" >/dev/null 2>&1; then
        die "Required dependency image for HBase is missing: upfuzz_hdfs:hadoop-2.10.2"
    fi
    sanitize_hbase_cached_classpath_for_version "${ORIGINAL_VERSION}"
    sanitize_hbase_cached_classpath_for_version "${UPGRADED_VERSION}"
fi

[[ "${FIXED_CONFIG_IDX}" =~ ^-?[0-9]+$ ]] || die "--fixed-config-idx must be an integer"
[[ "${HBASE_DAEMON_RETRY_TIMES}" =~ ^[0-9]+$ ]] || die "--hbase-daemon-retry-times must be a non-negative integer"

case "${TRACE_SYSTEM_PRESET}" in
    AUTO|GENERIC|CASSANDRA|HDFS|HBASE) ;;
    *) die "--trace-system-preset must be AUTO|GENERIC|CASSANDRA|HDFS|HBASE (got: ${TRACE_SYSTEM_PRESET})" ;;
esac

case "${ENABLE_FLOW_TUPLE_DUMP}" in
    true|false) ;;
    *) die "--enable-flow-tuple-dump must be true|false (got: ${ENABLE_FLOW_TUPLE_DUMP})" ;;
esac

case "${ENABLE_CHECKPOINT_RESTORE}" in
    true|false) ;;
    *) die "--enable-checkpoint-restore must be true|false (got: ${ENABLE_CHECKPOINT_RESTORE})" ;;
esac
case "${CHECKPOINT_ALL_LANES}" in
    true|false) ;;
    *) die "--checkpoint-all-lanes must be true|false (got: ${CHECKPOINT_ALL_LANES})" ;;
esac
case "${CHECKPOINT_REUSE}" in
    true|false) ;;
    *) die "--checkpoint-reuse must be true|false (got: ${CHECKPOINT_REUSE})" ;;
esac
case "${CHECKPOINT_ALLOW_NON_CASSANDRA}" in
    true|false) ;;
    *) die "--checkpoint-allow-non-cassandra must be true|false (got: ${CHECKPOINT_ALLOW_NON_CASSANDRA})" ;;
esac
case "${CHECKPOINT_WORKLOAD_ONLY_BENCHMARK}" in
    true|false) ;;
    *) die "--checkpoint-workload-only-benchmark must be true|false (got: ${CHECKPOINT_WORKLOAD_ONLY_BENCHMARK})" ;;
esac

[[ "${FLOW_TUPLE_DUMP_TOP_K}" =~ ^[0-9]+$ ]] || die "--flow-tuple-dump-top-k must be a non-negative integer (got: ${FLOW_TUPLE_DUMP_TOP_K})"

CHECKPOINT_SELECTED_NODES_JSON="$(json_int_array_from_csv "${CHECKPOINT_SELECTED_NODES}")"
IFS=',' read -r -a _checkpoint_nodes <<< "${CHECKPOINT_SELECTED_NODES}"
for _checkpoint_node in "${_checkpoint_nodes[@]}"; do
    _checkpoint_node="$(echo "${_checkpoint_node}" | tr -d '[:space:]')"
    if (( _checkpoint_node >= NODE_NUM )); then
        die "--checkpoint-selected-nodes contains ${_checkpoint_node}, but node-num=${NODE_NUM}"
    fi
done

if [[ "${ENABLE_CHECKPOINT_RESTORE}" == true ]]; then
    [[ "${TESTING_MODE}" == "5" ]] || die "--enable-checkpoint-restore is only supported with --testing-mode 5"
    [[ "${USE_DIFF}" == true ]] || die "--enable-checkpoint-restore requires differentialExecution/--diff mode"
    if [[ "${SYSTEM}" != "cassandra" && "${CHECKPOINT_ALLOW_NON_CASSANDRA}" != true ]]; then
        die "--enable-checkpoint-restore is Cassandra-first; pass --checkpoint-allow-non-cassandra true only after validating ${SYSTEM}"
    fi
    if ! docker checkpoint --help >/dev/null 2>&1; then
        log "Docker checkpoint CLI is unavailable; checkpoint mode will use image snapshot backend"
    fi
    docker_exp="$(docker version --format '{{.Server.Experimental}}' 2>/dev/null || true)"
    if [[ "${docker_exp}" != "true" ]]; then
        log "Docker daemon experimental mode is ${docker_exp:-unknown}; checkpoint mode will use image snapshot backend"
    fi
fi
if [[ "${CHECKPOINT_REUSE}" == true && "${ENABLE_CHECKPOINT_RESTORE}" != true ]]; then
    die "--checkpoint-reuse true requires --enable-checkpoint-restore true"
fi
if [[ "${CHECKPOINT_WORKLOAD_ONLY_BENCHMARK}" == true && "${ENABLE_CHECKPOINT_RESTORE}" != true ]]; then
    die "--checkpoint-workload-only-benchmark requires --enable-checkpoint-restore true"
fi

# Phase 5: auto-derive a family-map profile path when the user did not
# pass --family-map-profile explicitly. Empty string falls back to "no
# override" on the Java side.
if [[ -z "${FAMILY_MAP_PROFILE}" ]]; then
    FAMILY_MAP_PROFILE="$(auto_derive_family_map "${SYSTEM}" "${ORIGINAL_VERSION}")"
    if [[ -n "${FAMILY_MAP_PROFILE}" ]]; then
        log "Auto-derived family-map profile: ${FAMILY_MAP_PROFILE}"
    fi
elif [[ ! -f "${FAMILY_MAP_PROFILE}" ]]; then
    die "--family-map-profile points at a non-existent file: ${FAMILY_MAP_PROFILE}"
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

write_config_json "${CONFIG_PATH}" "${SYSTEM}" "${ORIGINAL_VERSION}" "${UPGRADED_VERSION}" "${NODE_NUM}" "${SERVER_PORT}" "${CLIENT_PORT}" "${FIXED_CONFIG_IDX}"

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
DIFF_LANE_TIMEOUT_SEC=${DIFF_LANE_TIMEOUT_SEC}
HBASE_DAEMON_RETRY_TIMES=${HBASE_DAEMON_RETRY_TIMES}
ENABLE_CHECKPOINT_RESTORE=${ENABLE_CHECKPOINT_RESTORE}
CHECKPOINT_SELECTED_NODES=${CHECKPOINT_SELECTED_NODES}
CHECKPOINT_ALL_LANES=${CHECKPOINT_ALL_LANES}
CHECKPOINT_CACHE_DIR=${CHECKPOINT_CACHE_DIR}
CHECKPOINT_REUSE=${CHECKPOINT_REUSE}
CHECKPOINT_ALLOW_NON_CASSANDRA=${CHECKPOINT_ALLOW_NON_CASSANDRA}
CHECKPOINT_WORKLOAD_ONLY_BENCHMARK=${CHECKPOINT_WORKLOAD_ONLY_BENCHMARK}
USE_TRACE=${USE_TRACE}
PRINT_TRACE=${PRINT_TRACE}
USE_CANONICAL_TRACE=${USE_CANONICAL_TRACE}
CANONICAL_WINDOW_SIM_THRESHOLD=${CANONICAL_WINDOW_SIM_THRESHOLD}
CANONICAL_AGGREGATE_SIM_THRESHOLD=${CANONICAL_AGGREGATE_SIM_THRESHOLD}
CANONICAL_WINDOW_DIVERGENCE_MARGIN=${CANONICAL_WINDOW_DIVERGENCE_MARGIN}
CANONICAL_AGGREGATE_DIVERGENCE_MARGIN=${CANONICAL_AGGREGATE_DIVERGENCE_MARGIN}
CANONICAL_MIN_WINDOW_EVENTS=${CANONICAL_MIN_WINDOW_EVENTS}
USE_CANONICAL_MESSAGE_IDENTITY=${USE_CANONICAL_MESSAGE_IDENTITY}
ROLLING_EXCLUSIVE_MIN_COUNT=${ROLLING_EXCLUSIVE_MIN_COUNT}
ROLLING_MISSING_MIN_COUNT=${ROLLING_MISSING_MIN_COUNT}
ROLLING_EXCLUSIVE_FRACTION_THRESHOLD=${ROLLING_EXCLUSIVE_FRACTION_THRESHOLD}
ROLLING_MISSING_FRACTION_THRESHOLD=${ROLLING_MISSING_FRACTION_THRESHOLD}
USE_BRANCH_COVERAGE=${USE_BRANCH_COVERAGE}
ENABLE_LOG_CHECK=${ENABLE_LOG_CHECK}
SERVER_PORT=${SERVER_PORT}
CLIENT_PORT=${CLIENT_PORT}
NODE_NUM=${NODE_NUM}
FIXED_CONFIG_IDX=${FIXED_CONFIG_IDX}
SERVER_START_TIMEOUT_SEC=${SERVER_START_TIMEOUT_SEC}
RUN_NAME=${RUN_NAME}
RUN_DIR=${RUN_DIR}
REQUIRE_TRACE_SIGNAL=${REQUIRE_TRACE_SIGNAL}
TRACE_SYSTEM_PRESET=${TRACE_SYSTEM_PRESET}
FAMILY_MAP_PROFILE=${FAMILY_MAP_PROFILE}
ENABLE_FLOW_TUPLE_DUMP=${ENABLE_FLOW_TUPLE_DUMP}
FLOW_TUPLE_DUMP_TOP_K=${FLOW_TUPLE_DUMP_TOP_K}
GIT_SHA=${GIT_SHA}
META

if [[ "${SKIP_PRE_CLEAN}" == false ]]; then
    log "Running pre-clean to remove old upfuzz processes/containers"
    (
        cd "${ROOT_DIR}"
        bin/clean.sh --force
    ) > "${PRE_CLEAN_LOG}" 2>&1 || true
    (
        cd "${ROOT_DIR}"
        cleanup_upfuzz_networks
    ) >> "${PRE_CLEAN_LOG}" 2>&1 || true
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

log "Waiting for server port ${SERVER_PORT} to listen (timeout=${SERVER_START_TIMEOUT_SEC}s)"
if wait_for_server_listen "${SERVER_PID}" "${SERVER_PORT}" "${SERVER_START_TIMEOUT_SEC}"; then
    log "Server is listening on ${SERVER_PORT}"
else
    rc=$?
    if [[ "${rc}" -eq 1 ]]; then
        die "Server exited before becoming ready. See ${SERVER_STDOUT} and ${SERVER_LOG}"
    fi
    die "Server did not listen on port ${SERVER_PORT} within ${SERVER_START_TIMEOUT_SEC}s"
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
HEALTHCHECK_MIN_EXEC=20
HEALTHCHECK_FAIL_ONLY_THRESHOLD=20

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
        all3_failed_count_now="$(safe_rg_count_file 'All 3 clusters failed' "${SERVER_LOG}")"
        log "Observed progress: total_exec=${rounds}, diff_feedback_packets=${diff_feedback_packets}, all3_failed=${all3_failed_count_now}"
        last_rounds="${rounds}"
    fi

    if [[ "${USE_TRACE}" == true && "${rounds}" -ge "${HEALTHCHECK_MIN_EXEC}" ]]; then
        all3_failed_count="$(safe_rg_count_file 'All 3 clusters failed' "${SERVER_LOG}")"
        trace_received_runtime="$(count_pattern_in_clients 'Received trace = ')"
        trace_len_positive_runtime="$(safe_rg_count_file 'trace\[[0-9]+\] len = [1-9][0-9]*' "${SERVER_LOG}")"
        if (( all3_failed_count >= HEALTHCHECK_FAIL_ONLY_THRESHOLD )) && (( trace_received_runtime == 0 )) && (( trace_len_positive_runtime == 0 )); then
            log "Health check failed: all clusters failed ${all3_failed_count} times with zero trace signal. Likely environment issue (for example missing docker compose)."
            stop_reason="healthcheck_all_clusters_failed"
            break
        fi
    fi

    if (( rounds >= TARGET_ROUNDS )); then
        if [[ "${USE_TRACE}" == true ]]; then
            tri_diff_count="$(safe_rg_count_file '\[TRACE\] Aggregate:|\[TRACE\] Window alignment failed|\[TRACE\] Windowed traces not available' "${SERVER_LOG}")"
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
(
    cd "${ROOT_DIR}"
    cleanup_upfuzz_networks
) >> "${POST_CLEAN_LOG}" 2>&1 || true

END_TS="$(date '+%F %T')"
END_EPOCH="$(date +%s)"
DURATION_SEC=$((END_EPOCH - START_EPOCH))

final_rounds="$(extract_total_exec "${SERVER_LOG}")"
final_diff_packets="$(count_diff_feedback_packets "${SERVER_LOG}")"

cp -f "${SERVER_LOG}" "${RUN_DIR}/upfuzz_server.log" || true
for i in $(seq 1 "${CLIENTS}"); do
    cp -f "${CLIENT_LOG_PREFIX}_${i}.log" "${RUN_DIR}/upfuzz_client_${i}.log" || true
done

# Phase 0 observability snapshot: per-round CSVs written by the server.
OBSERVABILITY_SRC="${ROOT_DIR}/failure/observability"
if [[ -d "${OBSERVABILITY_SRC}" ]]; then
    mkdir -p "${RUN_DIR}/observability"
    cp -f "${OBSERVABILITY_SRC}"/*.csv "${RUN_DIR}/observability/" 2>/dev/null || true
fi

rg -n 'TestPlanDiffFeedbackPacket received|Semantic Similarity|Semantic tri-diff|TRACE-LEGACY|Only Old|Rolling|Only New|total exec :|traceInteresting|addToCorpus' "${SERVER_LOG}" > "${RUN_DIR}/server_key_markers.log" 2>/dev/null || true
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
TRACE_MERGED_OLD_NONZERO_COUNT=0
TRACE_MERGED_ROLLING_NONZERO_COUNT=0
TRACE_MERGED_NEW_NONZERO_COUNT=0
TRACE_MERGED_ZERO_COUNT=0
MESSAGE_TRI_DIFF_COUNT=0
TRACE_SIGNAL_OK="n/a"

if [[ "${USE_TRACE}" == true ]]; then
    TRACE_CONNECT_REFUSED_COUNT="$(count_pattern_in_clients 'Connection refused')"
    TRACE_RECEIVED_COUNT="$(count_pattern_in_clients 'Received trace = ')"
    TRACE_LEN_POSITIVE_COUNT="$(safe_rg_count_file 'trace\[[0-9]+\] len = [1-9][0-9]*' "${SERVER_LOG}")"
    TRACE_LEN_ZERO_COUNT="$(safe_rg_count_file 'trace\[[0-9]+\] len = 0' "${SERVER_LOG}")"
    TRACE_MERGED_OLD_NONZERO_COUNT="$(safe_rg_count_file '=== Merged Trace 0 \(Only Old\), size=[1-9][0-9]* ===' "${SERVER_LOG}")"
    TRACE_MERGED_ROLLING_NONZERO_COUNT="$(safe_rg_count_file '=== Merged Trace 1 \(Rolling\), size=[1-9][0-9]* ===' "${SERVER_LOG}")"
    TRACE_MERGED_NEW_NONZERO_COUNT="$(safe_rg_count_file '=== Merged Trace 2 \(Only New\), size=[1-9][0-9]* ===' "${SERVER_LOG}")"
    TRACE_MERGED_ZERO_COUNT="$(safe_rg_count_file '=== Merged Trace [0-9]+ \([^)]*\), size=0 ===' "${SERVER_LOG}")"
    MESSAGE_TRI_DIFF_COUNT="$(safe_rg_count_file '\[TRACE\] Aggregate:|\[TRACE\] Window alignment failed|\[TRACE\] Windowed traces not available' "${SERVER_LOG}")"
    if (( TRACE_RECEIVED_COUNT > 0 || TRACE_LEN_POSITIVE_COUNT > 0 )); then
        TRACE_SIGNAL_OK=true
    else
        TRACE_SIGNAL_OK=false
    fi
fi

cat > "${SUMMARY_PATH}" <<SUMMARY
run_name: ${RUN_NAME}
git_sha: ${GIT_SHA}
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
enable_checkpoint_restore: ${ENABLE_CHECKPOINT_RESTORE}
checkpoint_reuse: ${CHECKPOINT_REUSE}
checkpoint_selected_nodes: ${CHECKPOINT_SELECTED_NODES}
checkpoint_all_lanes: ${CHECKPOINT_ALL_LANES}
checkpoint_workload_only_benchmark: ${CHECKPOINT_WORKLOAD_ONLY_BENCHMARK}
server_port: ${SERVER_PORT}
client_port: ${CLIENT_PORT}
new_failure_dirs: ${new_failure_count}
trace_enabled: ${USE_TRACE}
print_trace: ${PRINT_TRACE}
trace_signal_ok: ${TRACE_SIGNAL_OK}
trace_connect_refused_count: ${TRACE_CONNECT_REFUSED_COUNT}
trace_received_count: ${TRACE_RECEIVED_COUNT}
# Node-level trace lengths (per TestPlanFeedbackPacket.trace[nodeIdx]):
trace_len_positive_count: ${TRACE_LEN_POSITIVE_COUNT}
trace_len_zero_count: ${TRACE_LEN_ZERO_COUNT}
# Execution-level merged trace sizes (Only Old / Rolling / Only New):
trace_merged_old_nonzero_count: ${TRACE_MERGED_OLD_NONZERO_COUNT}
trace_merged_rolling_nonzero_count: ${TRACE_MERGED_ROLLING_NONZERO_COUNT}
trace_merged_new_nonzero_count: ${TRACE_MERGED_NEW_NONZERO_COUNT}
trace_merged_zero_count: ${TRACE_MERGED_ZERO_COUNT}
message_tri_diff_count: ${MESSAGE_TRI_DIFF_COUNT}
require_trace_signal: ${REQUIRE_TRACE_SIGNAL}
trace_system_preset: ${TRACE_SYSTEM_PRESET}
family_map_profile: ${FAMILY_MAP_PROFILE}
enable_flow_tuple_dump: ${ENABLE_FLOW_TUPLE_DUMP}
flow_tuple_dump_top_k: ${FLOW_TUPLE_DUMP_TOP_K}
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
        if (( TRACE_RECEIVED_COUNT == 0 )); then
            echo "ERROR: Trace collection observed Connection refused (${TRACE_CONNECT_REFUSED_COUNT} times) with no successful trace reads." >&2
            exit 4
        fi
        echo "WARNING: Trace collection observed Connection refused (${TRACE_CONNECT_REFUSED_COUNT} times) but successful trace reads were also observed (${TRACE_RECEIVED_COUNT})." >&2
    fi
fi

exit 0
