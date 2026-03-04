#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<USAGE
Usage:
  $(basename "$0") --host <host> [options]

Required:
  --host <host>                         Host directory name under raw-data-root

Options:
  --repo-root <path>                    upfuzz repo root (default: /users/swang516/xlab/rupfuzz/upfuzz)
  --raw-data-root <path>                raw_data root (default: /users/swang516/xlab/rupfuzz/cloudlab-results/feb26/raw_data)
  --out-root <path>                     output root (default: /users/swang516/xlab/rupfuzz/cloudlab-results/feb26/analyze_data/repro_all)
  --per-failure-timeout-sec <sec>       hard timeout per failure replay (default: 2400)
  --runner-timeout-sec <sec>            --timeout-sec passed to reproducer (default: 1800)
  --max-attempts <N>                    replay attempts per failure (default: 3)
  --rounds-per-attempt <N>              rounds passed to reproducer for each attempt (default: 1)
  --command-node-strategies <csv>       legacy command-node replay strategies
                                        (default: preserve,round_robin,random)
  --command-node-random-base <N>        base seed for random strategy (default: 1337)
  --only-failure-ids <csv>              optional subset, e.g. failure_1,failure_7
  --resume                              skip failures already present in summary.tsv
  -h, --help                            Show help

This script runs failures sequentially on one machine.
USAGE
}

HOST=""
REPO_ROOT="/users/swang516/xlab/rupfuzz/upfuzz"
RAW_DATA_ROOT="/users/swang516/xlab/rupfuzz/cloudlab-results/feb26/raw_data"
OUT_ROOT="/users/swang516/xlab/rupfuzz/cloudlab-results/feb26/analyze_data/repro_all"
PER_FAILURE_TIMEOUT_SEC=2400
RUNNER_TIMEOUT_SEC=1800
MAX_ATTEMPTS=3
ROUNDS_PER_ATTEMPT=1
COMMAND_NODE_STRATEGIES="preserve,round_robin,random"
COMMAND_NODE_RANDOM_BASE=1337
ONLY_FAILURE_IDS=""
RESUME=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --host)
            HOST="$2"
            shift 2
            ;;
        --repo-root)
            REPO_ROOT="$2"
            shift 2
            ;;
        --raw-data-root)
            RAW_DATA_ROOT="$2"
            shift 2
            ;;
        --out-root)
            OUT_ROOT="$2"
            shift 2
            ;;
        --per-failure-timeout-sec)
            PER_FAILURE_TIMEOUT_SEC="$2"
            shift 2
            ;;
        --runner-timeout-sec)
            RUNNER_TIMEOUT_SEC="$2"
            shift 2
            ;;
        --max-attempts)
            MAX_ATTEMPTS="$2"
            shift 2
            ;;
        --rounds-per-attempt)
            ROUNDS_PER_ATTEMPT="$2"
            shift 2
            ;;
        --command-node-strategies)
            COMMAND_NODE_STRATEGIES="$2"
            shift 2
            ;;
        --command-node-random-base)
            COMMAND_NODE_RANDOM_BASE="$2"
            shift 2
            ;;
        --only-failure-ids)
            ONLY_FAILURE_IDS="$2"
            shift 2
            ;;
        --resume)
            RESUME=true
            shift 1
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown arg: $1" >&2
            usage
            exit 1
            ;;
    esac
done

[[ -n "${HOST}" ]] || { echo "--host is required" >&2; exit 1; }

log() {
    printf '[%s] %s\n' "$(date '+%F %T')" "$*"
}

fail_dir_root="${RAW_DATA_ROOT}/${HOST}/failure"
meta_path="${RAW_DATA_ROOT}/${HOST}/runner_result/metadata.env"
[[ -d "${fail_dir_root}" ]] || { echo "Missing failure root: ${fail_dir_root}" >&2; exit 1; }
[[ -f "${meta_path}" ]] || { echo "Missing metadata: ${meta_path}" >&2; exit 1; }

SYSTEM="$(awk -F= '/^SYSTEM=/{print $2}' "${meta_path}" | tail -n1)"
ORIGINAL_VERSION="$(awk -F= '/^ORIGINAL_VERSION=/{print $2}' "${meta_path}" | tail -n1)"
UPGRADED_VERSION="$(awk -F= '/^UPGRADED_VERSION=/{print $2}' "${meta_path}" | tail -n1)"
TESTING_MODE="$(awk -F= '/^TESTING_MODE=/{print $2}' "${meta_path}" | tail -n1)"
META_DIFF_LANE_TIMEOUT_SEC="$(awk -F= '/^DIFF_LANE_TIMEOUT_SEC=/{print $2}' "${meta_path}" | tail -n1)"

[[ "${MAX_ATTEMPTS}" =~ ^[0-9]+$ ]] || { echo "Invalid --max-attempts: ${MAX_ATTEMPTS}" >&2; exit 1; }
[[ "${ROUNDS_PER_ATTEMPT}" =~ ^[0-9]+$ ]] || { echo "Invalid --rounds-per-attempt: ${ROUNDS_PER_ATTEMPT}" >&2; exit 1; }
[[ "${MAX_ATTEMPTS}" -ge 1 ]] || { echo "--max-attempts must be >= 1" >&2; exit 1; }
[[ "${ROUNDS_PER_ATTEMPT}" -ge 1 ]] || { echo "--rounds-per-attempt must be >= 1" >&2; exit 1; }
[[ "${RUNNER_TIMEOUT_SEC}" =~ ^[0-9]+$ ]] || { echo "Invalid --runner-timeout-sec: ${RUNNER_TIMEOUT_SEC}" >&2; exit 1; }
[[ "${PER_FAILURE_TIMEOUT_SEC}" =~ ^[0-9]+$ ]] || { echo "Invalid --per-failure-timeout-sec: ${PER_FAILURE_TIMEOUT_SEC}" >&2; exit 1; }
[[ "${COMMAND_NODE_RANDOM_BASE}" =~ ^[0-9]+$ ]] || { echo "Invalid --command-node-random-base: ${COMMAND_NODE_RANDOM_BASE}" >&2; exit 1; }

if [[ "${META_DIFF_LANE_TIMEOUT_SEC}" =~ ^[0-9]+$ ]] && [[ "${META_DIFF_LANE_TIMEOUT_SEC}" -gt 0 ]]; then
    min_runner_timeout=$((META_DIFF_LANE_TIMEOUT_SEC + 300))
    if [[ "${RUNNER_TIMEOUT_SEC}" -lt "${min_runner_timeout}" ]]; then
        RUNNER_TIMEOUT_SEC="${min_runner_timeout}"
    fi
fi
min_per_failure_timeout=$((RUNNER_TIMEOUT_SEC + 180))
if [[ "${PER_FAILURE_TIMEOUT_SEC}" -lt "${min_per_failure_timeout}" ]]; then
    PER_FAILURE_TIMEOUT_SEC="${min_per_failure_timeout}"
fi

IFS=',' read -r -a NODE_STRATEGY_ARRAY <<< "${COMMAND_NODE_STRATEGIES}"
VALID_NODE_STRATEGIES=(preserve zero round_robin random)
for s in "${NODE_STRATEGY_ARRAY[@]}"; do
    case "${s}" in
        preserve|zero|round_robin|random) ;;
        *)
            echo "Invalid strategy in --command-node-strategies: ${s}" >&2
            exit 1
            ;;
    esac
done
[[ "${#NODE_STRATEGY_ARRAY[@]}" -gt 0 ]] || { echo "No strategy provided in --command-node-strategies" >&2; exit 1; }

HOST_OUT="${OUT_ROOT}/${HOST}"
mkdir -p "${HOST_OUT}"
summary_tsv="${HOST_OUT}/summary.tsv"

if [[ ! -f "${summary_tsv}" || "${RESUME}" == false ]]; then
    cat > "${summary_tsv}" <<'TSV'
failure_id	category	raw_signature	repro_exit	repro_timeout	reproduced	matched_pattern	run_name	run_dir	analysis_file
TSV
fi

classify_category() {
    local fdir="$1"
    if rg -q "RollingUpgradeException: Failed to start rolling upgrade since a rolling upgrade is already in progress" "$fdir"; then
        echo "hdfs_rolling_upgrade_already_in_progress"
    elif rg -q "\\[ERROR LOG\\]" "$fdir"; then
        echo "${SYSTEM}_error_log"
    elif rg -q "RecoverableZooKeeper: ZooKeeper getData failed" "$fdir"; then
        echo "hbase_zookeeper_getdata_failed"
    elif rg -q "ActiveMasterManager: Failed get of master address" "$fdir"; then
        echo "hbase_master_address_znode_null"
    elif rg -q "TableNotEnabledException" "$fdir"; then
        echo "hbase_table_not_enabled"
    elif rg -q "NullPointerException" "$fdir"; then
        echo "hbase_null_pointer_exception"
    elif rg -q "Undefined column name" "$fdir"; then
        echo "cassandra_undefined_column"
    elif rg -q "ReadTimeout" "$fdir"; then
        echo "cassandra_read_timeout"
    elif rg -q "container .* is not running" "$fdir"; then
        echo "container_not_running"
    elif rg -q "Results inconsistency between full-stop and rolling upgrade" "$fdir"; then
        case "${SYSTEM}" in
            cassandra) echo "cassandra_result_inconsistency" ;;
            hdfs) echo "hdfs_result_inconsistency" ;;
            hbase) echo "hbase_result_inconsistency" ;;
            *) echo "result_inconsistency" ;;
        esac
    elif rg -q "Cross-cluster inconsistency detected" "$fdir"; then
        case "${SYSTEM}" in
            cassandra) echo "cassandra_result_inconsistency" ;;
            hdfs) echo "hdfs_result_inconsistency" ;;
            hbase) echo "hbase_result_inconsistency" ;;
            *) echo "result_inconsistency" ;;
        esac
    else
        echo "unknown"
    fi
}

classify_category_from_signature() {
    local sig="$1"
    if [[ "${sig}" =~ RollingUpgradeException:\ Failed\ to\ start\ rolling\ upgrade\ since\ a\ rolling\ upgrade\ is\ already\ in\ progress ]]; then
        echo "hdfs_rolling_upgrade_already_in_progress"
    elif [[ "${sig}" =~ \[ERROR\ LOG\] ]]; then
        echo "${SYSTEM}_error_log"
    elif [[ "${sig}" =~ RecoverableZooKeeper:\ ZooKeeper\ getData\ failed ]]; then
        echo "hbase_zookeeper_getdata_failed"
    elif [[ "${sig}" =~ Failed\ get\ of\ master\ address ]]; then
        echo "hbase_master_address_znode_null"
    elif [[ "${sig}" =~ TableNotEnabledException ]]; then
        echo "hbase_table_not_enabled"
    elif [[ "${sig}" =~ NullPointerException ]]; then
        echo "hbase_null_pointer_exception"
    elif [[ "${sig}" =~ Undefined\ column\ name ]]; then
        echo "cassandra_undefined_column"
    elif [[ "${sig}" =~ ReadTimeout ]]; then
        echo "cassandra_read_timeout"
    elif [[ "${sig}" =~ container\ .*is\ not\ running ]]; then
        echo "container_not_running"
    elif [[ "${sig}" =~ Results\ inconsistency\ between\ full-stop\ and\ rolling\ upgrade ]]; then
        case "${SYSTEM}" in
            cassandra) echo "cassandra_result_inconsistency" ;;
            hdfs) echo "hdfs_result_inconsistency" ;;
            hbase) echo "hbase_result_inconsistency" ;;
            *) echo "result_inconsistency" ;;
        esac
    elif [[ "${sig}" =~ Cross-cluster\ inconsistency\ detected ]]; then
        case "${SYSTEM}" in
            cassandra) echo "cassandra_result_inconsistency" ;;
            hdfs) echo "hdfs_result_inconsistency" ;;
            hbase) echo "hbase_result_inconsistency" ;;
            *) echo "result_inconsistency" ;;
        esac
    else
        echo "unknown"
    fi
}

extract_raw_signature() {
    local fdir="$1"
    local sig
    sig="$(find "$fdir" -type f \( -name 'error_*.report' -o -name 'error_log_*.report' -o -name 'inconsistency_*.report' -o -name 'inconsistency_crosscluster_*.report' -o -name 'event_crash_*.report' \) | sort -V | while read -r f; do sed -n '1,80p' "$f"; done | rg -m1 "RollingUpgradeException|RecoverableZooKeeper|ConnectionLossException|Failed get of master address|TableNotEnabledException|NullPointerException|Undefined column name|ReadTimeout|Results inconsistency|Cross-cluster inconsistency|container .* is not running|AssertionError" || true)"
    if [[ -z "${sig}" ]]; then
        sig="$(find "$fdir" -type f | sort -V | head -n1 | xargs -r sed -n '1,6p' | tr '\n' ' ' | sed 's/[[:space:]]\+/ /g' | cut -c1-220 || true)"
    fi
    echo "${sig}"
}

is_reproduced() {
    local category="$1"
    local logfile="$2"
    local pattern=""

    case "${category}" in
        hdfs_rolling_upgrade_already_in_progress)
            pattern="RollingUpgradeException: Failed to start rolling upgrade since a rolling upgrade is already in progress"
            ;;
        hbase_zookeeper_getdata_failed)
            pattern="RecoverableZooKeeper: ZooKeeper getData failed|Unable to get data of znode /hbase/master|Zookeeper LIST could not be completed"
            ;;
        hbase_master_address_znode_null)
            pattern="Failed get of master address"
            ;;
        hbase_table_not_enabled)
            pattern="TableNotEnabledException"
            ;;
        hbase_null_pointer_exception)
            pattern="NullPointerException"
            ;;
        cassandra_undefined_column)
            pattern="Undefined column name"
            ;;
        cassandra_read_timeout)
            pattern="ReadTimeout"
            ;;
        cassandra_error_log|hdfs_error_log|hbase_error_log)
            pattern="\\[ERROR LOG\\]|ERROR LOG"
            ;;
        container_not_running)
            pattern="container .* is not running"
            ;;
        cassandra_result_inconsistency|hdfs_result_inconsistency|hbase_result_inconsistency|result_inconsistency)
            pattern="Results inconsistency between full-stop and rolling upgrade|Cross-cluster inconsistency detected|Structured validation divergence"
            ;;
        *)
            pattern="AssertionError|Exception|ERROR LOG"
            ;;
    esac

    if rg -q "${pattern}" "${logfile}"; then
        echo "true|${pattern}"
    elif [[ "${category}" == *"result_inconsistency"* ]] \
        && rg -q 'new_failure_dirs: [1-9][0-9]*' "${logfile}"; then
        # Replay console logs may not print full inconsistency text, but a new
        # failure directory means the lane-oracle inconsistency was triggered.
        echo "true|new_failure_dirs"
    elif [[ "${category}" == *"_error_log" || "${category}" == "unknown" ]] \
        && rg -q 'new_failure_dirs: [1-9][0-9]*' "${logfile}"; then
        echo "true|new_failure_dirs"
    else
        echo "false|${pattern}"
    fi
}

cleanup_runtime() {
    local repo_root="$1"
    set +e
    pkill -f "scripts/cloudlab-reproducer/run_reproduce_from_raw_data.sh" >/dev/null 2>&1
    pkill -f "scripts/runner/run_rolling_fuzzing.sh" >/dev/null 2>&1
    pkill -f "org/zlab/upfuzz/fuzzingengine/Main -class server" >/dev/null 2>&1
    pkill -f "org/zlab/upfuzz/fuzzingengine/Main -class client" >/dev/null 2>&1
    cd "${repo_root}" || return 0
    bin/clean.sh --force >/dev/null 2>&1
    docker rm -f $(docker ps -aq) >/dev/null 2>&1
    docker network prune -f >/dev/null 2>&1
    set -e
}

if [[ "${RESUME}" == true ]]; then
    done_set="$(awk 'NR>1 {print $1}' "${summary_tsv}" | sort -u || true)"
else
    done_set=""
fi

only_set=""
if [[ -n "${ONLY_FAILURE_IDS}" ]]; then
    only_set="$(echo "${ONLY_FAILURE_IDS}" | tr ',' '\n' | sed '/^[[:space:]]*$/d' | sort -u)"
fi

log "Host=${HOST} system=${SYSTEM} ${ORIGINAL_VERSION}->${UPGRADED_VERSION}"
log "Testing mode from metadata=${TESTING_MODE:-unknown}, max_attempts=${MAX_ATTEMPTS}, rounds_per_attempt=${ROUNDS_PER_ATTEMPT}"
log "Timeouts: per_failure=${PER_FAILURE_TIMEOUT_SEC}s runner=${RUNNER_TIMEOUT_SEC}s (meta lane timeout=${META_DIFF_LANE_TIMEOUT_SEC:-n/a}s)"
log "Command-node strategies: ${COMMAND_NODE_STRATEGIES} (random_base=${COMMAND_NODE_RANDOM_BASE})"
log "Output=${HOST_OUT}"

find "${fail_dir_root}" -maxdepth 1 -type d -name 'failure_*' | sort -V | while read -r fdir; do
    fid="$(basename "${fdir}")"
    if [[ -n "${only_set}" ]] && ! echo "${only_set}" | rg -qx "${fid}"; then
        continue
    fi
    if [[ "${RESUME}" == true ]] && echo "${done_set}" | rg -qx "${fid}"; then
        log "[${fid}] skip (already in summary)"
        continue
    fi

    raw_sig="$(extract_raw_signature "${fdir}")"
    category="$(classify_category_from_signature "${raw_sig}")"
    if [[ "${category}" == "unknown" ]]; then
        category="$(classify_category "${fdir}")"
    fi

    per_out_dir="${HOST_OUT}/${fid}"
    mkdir -p "${per_out_dir}"
    attempts_tsv="${per_out_dir}/attempts.tsv"
    echo -e "attempt\trun_name\treplay_exit_code\treplay_timeout\treproduced\tmatched_pattern\tcommand_node_strategy\tcommand_node_seed" > "${attempts_tsv}"
    merged_log="${per_out_dir}/merged_repro.log"
    : > "${merged_log}"

    log "[${fid}] category=${category}"
    reproduced=false
    matched_pattern=""
    rc=1
    timed_out=false
    run_name=""
    run_dir=""
    attempt=1

    while [[ "${attempt}" -le "${MAX_ATTEMPTS}" ]]; do
        ts="$(date '+%Y%m%d_%H%M%S')"
        run_name="repro_${HOST//[^a-zA-Z0-9]/_}_${fid}_a${attempt}_${ts}"
        run_dir="${REPO_ROOT}/scripts/runner/results/${run_name}"
        attempt_dir="${per_out_dir}/attempt_${attempt}"
        mkdir -p "${attempt_dir}"

        log "[${fid}] attempt ${attempt}/${MAX_ATTEMPTS}: run_name=${run_name}"
        strategy_idx=$(( (attempt - 1) % ${#NODE_STRATEGY_ARRAY[@]} ))
        command_node_strategy="${NODE_STRATEGY_ARRAY[${strategy_idx}]}"
        fid_num="$(echo "${fid}" | sed -E 's/[^0-9]//g')"
        if [[ -z "${fid_num}" ]]; then
            fid_num=0
        fi
        command_node_seed=$((COMMAND_NODE_RANDOM_BASE + fid_num * 17 + attempt))
        log "[${fid}] attempt ${attempt}: command_node_strategy=${command_node_strategy}, command_node_seed=${command_node_seed}"

        set +e
        timeout --signal=TERM "${PER_FAILURE_TIMEOUT_SEC}s" \
            "${REPO_ROOT}/scripts/cloudlab-reproducer/run_reproduce_from_raw_data.sh" \
            --raw-data-root "${RAW_DATA_ROOT}" \
            --host "${HOST}" \
            --failure-id "${fid}" \
            --rounds "${ROUNDS_PER_ATTEMPT}" \
            --timeout-sec "${RUNNER_TIMEOUT_SEC}" \
            --run-name "${run_name}" \
            --skip-pre-clean false \
            --use-trace true \
            --print-trace true \
            --command-node-strategy "${command_node_strategy}" \
            --command-node-seed "${command_node_seed}" \
            > "${attempt_dir}/repro_driver.log" 2>&1
        rc=$?
        set -e

        timed_out=false
        if [[ "${rc}" -eq 124 || "${rc}" -eq 137 ]]; then
            timed_out=true
        fi

        if [[ -d "${run_dir}" ]]; then
            cp -a "${run_dir}" "${attempt_dir}/" || true
        fi

        cp -f "${REPO_ROOT}/logs/upfuzz_server.log" "${attempt_dir}/upfuzz_server.log" 2>/dev/null || true
        cp -f "${REPO_ROOT}/logs/upfuzz_client_1.log" "${attempt_dir}/upfuzz_client_1.log" 2>/dev/null || true

        attempt_merged="${attempt_dir}/merged_repro_attempt.log"
        cat "${attempt_dir}/repro_driver.log" > "${attempt_merged}"
        echo >> "${attempt_merged}"
        if [[ -f "${attempt_dir}/upfuzz_server.log" ]]; then
            echo "===== upfuzz_server.log =====" >> "${attempt_merged}"
            cat "${attempt_dir}/upfuzz_server.log" >> "${attempt_merged}"
            echo >> "${attempt_merged}"
        fi
        if [[ -f "${attempt_dir}/upfuzz_client_1.log" ]]; then
            echo "===== upfuzz_client_1.log =====" >> "${attempt_merged}"
            cat "${attempt_dir}/upfuzz_client_1.log" >> "${attempt_merged}"
            echo >> "${attempt_merged}"
        fi
        if [[ -d "${attempt_dir}/${run_name}" ]]; then
            find "${attempt_dir}/${run_name}" -maxdepth 2 -type f \( -name '*.log' -o -name '*.txt' \) | sort | while read -r rf; do
                echo "===== ${rf} =====" >> "${attempt_merged}"
                sed -n '1,240p' "${rf}" >> "${attempt_merged}" || true
                echo >> "${attempt_merged}"
            done
        fi

        echo "===== ATTEMPT ${attempt} (${run_name}) =====" >> "${merged_log}"
        cat "${attempt_merged}" >> "${merged_log}"
        echo >> "${merged_log}"

        rep_info="$(is_reproduced "${category}" "${attempt_merged}")"
        attempt_reproduced="${rep_info%%|*}"
        attempt_pattern="${rep_info#*|}"
        printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" "${attempt}" "${run_name}" "${rc}" "${timed_out}" "${attempt_reproduced}" "${attempt_pattern}" "${command_node_strategy}" "${command_node_seed}" >> "${attempts_tsv}"

        if [[ "${attempt_reproduced}" == "true" ]]; then
            reproduced=true
            matched_pattern="${attempt_pattern}"
            log "[${fid}] reproduced on attempt ${attempt}"
            cleanup_runtime "${REPO_ROOT}"
            break
        fi

        matched_pattern="${attempt_pattern}"
        cleanup_runtime "${REPO_ROOT}"
        attempt=$((attempt + 1))
    done

    analysis_file="${per_out_dir}/analysis.md"
    {
        echo "# ${fid}"
        echo
        echo "- host: ${HOST}"
        echo "- system: ${SYSTEM}"
        echo "- versions: ${ORIGINAL_VERSION} -> ${UPGRADED_VERSION}"
        echo "- category: ${category}"
        echo "- raw_signature: ${raw_sig}"
        echo "- run_name: ${run_name}"
        echo "- replay_exit_code: ${rc}"
        echo "- replay_timeout: ${timed_out}"
        echo "- reproduced: ${reproduced}"
        echo "- matched_pattern: ${matched_pattern}"
        echo "- max_attempts: ${MAX_ATTEMPTS}"
        echo "- rounds_per_attempt: ${ROUNDS_PER_ATTEMPT}"
        echo
        echo "## Replay Attempts"
        cat "${attempts_tsv}"
        echo
        echo "## Raw Snippet"
        find "${fdir}" -type f \( -name 'error_*.report' -o -name 'error_log_*.report' -o -name 'inconsistency_*.report' -o -name 'inconsistency_crosscluster_*.report' -o -name 'event_crash_*.report' -o -name 'fullSequence_*.report' \) | sort -V | while read -r ff; do
            echo "### ${ff}"
            sed -n '1,80p' "${ff}"
            echo
        done
        echo
        echo "## Repro Key Snippet"
        rg -n "RollingUpgradeException|already in progress|RecoverableZooKeeper|ConnectionLossException|Zookeeper LIST could not be completed|Failed get of master address|TableNotEnabledException|NullPointerException|Undefined column name|ReadTimeout|Results inconsistency|Cross-cluster inconsistency|Structured validation divergence|container .* is not running|AssertionError|All 3 clusters failed|trace\[[0-9]+\] len =|Message identity tri-diff|TestPlanDiffFeedbackPacket received|total exec :" "${merged_log}" | sed -n '1,200p' || true
    } > "${analysis_file}"

    printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
        "${fid}" "${category}" "${raw_sig}" "${rc}" "${timed_out}" "${reproduced}" "${matched_pattern}" "${run_name}" "${run_dir}" "${analysis_file}" >> "${summary_tsv}"
    log "[${fid}] done: reproduced=${reproduced}, timeout=${timed_out}, rc=${rc}, attempts=$(awk 'NR>1{c++}END{print c+0}' "${attempts_tsv}")"
done

# Per-host summary
host_report="${HOST_OUT}/host_report.md"
{
    echo "# Host Reproduction Report: ${HOST}"
    echo
    echo "- system: ${SYSTEM}"
    echo "- versions: ${ORIGINAL_VERSION} -> ${UPGRADED_VERSION}"
    echo
    echo "## Stats"
    total="$(awk 'NR>1 {c++} END {print c+0}' "${summary_tsv}")"
    reproduced_cnt="$(awk -F'\t' 'NR>1 && $6=="true" {c++} END {print c+0}' "${summary_tsv}")"
    timeout_cnt="$(awk -F'\t' 'NR>1 && $5=="true" {c++} END {print c+0}' "${summary_tsv}")"
    echo "- total_failures: ${total}"
    echo "- reproduced_true: ${reproduced_cnt}"
    echo "- timed_out: ${timeout_cnt}"
    echo
    echo "## Category Breakdown"
    awk -F'\t' 'NR>1 {c[$2]++} END {for (k in c) printf "- %s: %d\n", k, c[k]}' "${summary_tsv}" | sort
    echo
    echo "## Reproduced by Category"
    awk -F'\t' 'NR>1 {key=$2"|"$6; c[key]++} END {for (k in c) {split(k,a,"|"); printf "- %s reproduced=%s: %d\n", a[1], a[2], c[k]}}' "${summary_tsv}" | sort
} > "${host_report}"

log "Host batch reproduction finished: ${host_report}"
