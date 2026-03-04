#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

RAW_DATA_ROOT=""
HOST=""
FAILURE_ID=""
FAILURE_DIR=""
SYSTEM=""
ORIGINAL_VERSION=""
UPGRADED_VERSION=""
ROUND_NUM=1
TIMEOUT_SEC=3600
CLIENTS=1
RUN_NAME=""
SKIP_PRE_CLEAN=true
USE_TRACE=true
PRINT_TRACE=true
TESTING_MODE=""
TESTING_MODE_EXPLICIT=false
FIXED_CONFIG_IDX=""
COMMAND_NODE_STRATEGY="preserve"
COMMAND_NODE_SEED=1

usage() {
    cat <<USAGE
Usage:
  $(basename "$0") [options]

Required (choose one input mode):
  A) --raw-data-root <dir> --host <cloudlab-host> --failure-id <failure_N>
  B) --failure-dir <abs/path/to/failure_N>

Version/config resolution:
  - If --raw-data-root + --host are provided, metadata is read from:
      <raw-data-root>/<host>/runner_result/metadata.env
  - Otherwise pass all explicitly:
      --system <cassandra|hdfs|hbase> --original <ver> --upgraded <ver>

Options:
  --raw-data-root <dir>      Root like .../cloudlab-results/feb26/raw_data
  --host <hostname>          Cloudlab host dir name under raw-data-root
  --failure-id <failure_N>   Failure dir name under <host>/failure
  --failure-dir <dir>        Direct path to a failure_N directory
  --system <name>            cassandra|hdfs|hbase
  --original <version>       Original version tag
  --upgraded <version>       Upgraded version tag
  --rounds <N>               Target rounds for replay (default: ${ROUND_NUM})
  --timeout-sec <N>          Timeout for replay run (default: ${TIMEOUT_SEC})
  --clients <N>              Clients count (default: ${CLIENTS})
  --run-name <name>          Optional run name suffix
  --testing-mode <N>         Testing mode override for replay (default: 3 for exact plan replay)
  --use-trace <true|false>   Enable trace during replay (default: ${USE_TRACE})
  --print-trace <true|false> Print trace details (default: ${PRINT_TRACE})
  --command-node-strategy <preserve|zero|round_robin|random>
                              Replay strategy for legacy test plans without
                              command node identity (default: ${COMMAND_NODE_STRATEGY})
  --command-node-seed <N>    Seed for --command-node-strategy random (default: ${COMMAND_NODE_SEED})
  --skip-pre-clean <true|false>  Skip clean before run (default: ${SKIP_PRE_CLEAN})
  -h, --help                 Show this help

Examples:
  $(basename "$0") \
    --raw-data-root /home/shuai/xlab/rupfuzz/cloudlab-results/feb26/raw_data \
    --host c220g5-111230.wisc.cloudlab.us \
    --failure-id failure_10

  $(basename "$0") \
    --failure-dir /tmp/failure_10 \
    --system hdfs --original hadoop-2.10.2 --upgraded hadoop-3.3.6
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

parse_bool() {
    case "$1" in
        true|false) echo "$1" ;;
        *) die "Invalid boolean value: $1 (expected true|false)" ;;
    esac
}

rewrite_legacy_command_nodes() {
    local in_path="$1"
    local out_path="$2"
    local strategy="$3"
    local node_num="$4"
    local seed="$5"

    local cmd_idx=0
    local line
    local node
    local payload

    if [[ "${strategy}" == "random" ]]; then
        RANDOM="${seed}"
    fi

    : > "${out_path}"
    while IFS= read -r line; do
        if [[ "${line}" =~ ^\[Command\](\[Node\[[0-9]+\]\])?\ Execute\ \{(.*)\}$ ]]; then
            if [[ -n "${BASH_REMATCH[1]}" ]]; then
                printf '%s\n' "${line}" >> "${out_path}"
                continue
            fi
            payload="${BASH_REMATCH[2]}"
            case "${strategy}" in
                zero)
                    node=0
                    ;;
                round_robin)
                    node=$((cmd_idx % node_num))
                    ;;
                random)
                    node=$((RANDOM % node_num))
                    ;;
                *)
                    node=0
                    ;;
            esac
            printf '[Command][Node[%d]] Execute {%s}\n' "${node}" "${payload}" >> "${out_path}"
            cmd_idx=$((cmd_idx + 1))
            continue
        fi
        printf '%s\n' "${line}" >> "${out_path}"
    done < "${in_path}"
}

base64_no_wrap() {
    local in_file="$1"
    if base64 --help 2>&1 | rg -q -- '-w'; then
        base64 -w 0 "${in_file}"
    else
        base64 "${in_file}" | tr -d '\n'
    fi
}

append_oracle_entry() {
    local read_id="$1"
    local full_stop_tmp="$2"
    local oracle_tmp="$3"

    [[ -n "${read_id}" ]] || return
    [[ -f "${full_stop_tmp}" ]] || return
    local payload_b64
    payload_b64="$(base64_no_wrap "${full_stop_tmp}")"
    printf '%s\t%s\n' "${read_id}" "${payload_b64}" >> "${oracle_tmp}"
}

build_oracle_from_inconsistency_report() {
    local report_path="$1"
    local oracle_out="$2"

    local oracle_tmp
    oracle_tmp="$(mktemp)"
    : > "${oracle_tmp}"

    local current_read_id=""
    local collecting_full_stop=false
    local full_stop_tmp=""
    local line

    while IFS= read -r line || [[ -n "${line}" ]]; do
        if [[ "${line}" =~ ^(Insignificant[[:space:]]+)?Result[[:space:]]+inconsistency[[:space:]]+at[[:space:]]+read[[:space:]]+id:[[:space:]]*([0-9]+)$ ]]; then
            if [[ -n "${full_stop_tmp}" ]]; then
                append_oracle_entry "${current_read_id}" "${full_stop_tmp}" "${oracle_tmp}"
                rm -f "${full_stop_tmp}"
                full_stop_tmp=""
            fi
            current_read_id="${BASH_REMATCH[2]}"
            full_stop_tmp="$(mktemp)"
            : > "${full_stop_tmp}"
            collecting_full_stop=false
            continue
        fi

        if [[ "${line}" == "Full Stop Result:" ]]; then
            collecting_full_stop=true
            continue
        fi

        if [[ "${line}" == "Rolling Upgrade Result:" ]]; then
            collecting_full_stop=false
            if [[ -n "${full_stop_tmp}" ]]; then
                append_oracle_entry "${current_read_id}" "${full_stop_tmp}" "${oracle_tmp}"
                rm -f "${full_stop_tmp}"
                full_stop_tmp=""
                current_read_id=""
            fi
            continue
        fi

        if [[ "${collecting_full_stop}" == true ]]; then
            if [[ -n "${full_stop_tmp}" ]]; then
                printf '%s\n' "${line}" >> "${full_stop_tmp}"
            fi
        fi
    done < "${report_path}"

    if [[ -n "${full_stop_tmp}" ]]; then
        append_oracle_entry "${current_read_id}" "${full_stop_tmp}" "${oracle_tmp}"
        rm -f "${full_stop_tmp}"
    fi

    if [[ -s "${oracle_tmp}" ]]; then
        sort -t $'\t' -k1,1n "${oracle_tmp}" | awk -F '\t' '!seen[$1]++ {print $0}' > "${oracle_out}"
    else
        : > "${oracle_out}"
    fi

    rm -f "${oracle_tmp}"
    wc -l < "${oracle_out}" | tr -d ' '
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --raw-data-root)
            RAW_DATA_ROOT="$2"
            shift 2
            ;;
        --host)
            HOST="$2"
            shift 2
            ;;
        --failure-id)
            FAILURE_ID="$2"
            shift 2
            ;;
        --failure-dir)
            FAILURE_DIR="$2"
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
        --rounds)
            ROUND_NUM="$2"
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
        --run-name)
            RUN_NAME="$2"
            shift 2
            ;;
        --testing-mode)
            TESTING_MODE="$2"
            TESTING_MODE_EXPLICIT=true
            shift 2
            ;;
        --use-trace)
            USE_TRACE="$(parse_bool "$2")"
            shift 2
            ;;
        --print-trace)
            PRINT_TRACE="$(parse_bool "$2")"
            shift 2
            ;;
        --command-node-strategy)
            COMMAND_NODE_STRATEGY="$2"
            shift 2
            ;;
        --command-node-seed)
            COMMAND_NODE_SEED="$2"
            shift 2
            ;;
        --skip-pre-clean)
            SKIP_PRE_CLEAN="$(parse_bool "$2")"
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

require_cmd rg
require_cmd awk
require_cmd base64

if [[ -n "${FAILURE_DIR}" ]]; then
    [[ -d "${FAILURE_DIR}" ]] || die "Failure directory does not exist: ${FAILURE_DIR}"
else
    [[ -n "${RAW_DATA_ROOT}" ]] || die "--raw-data-root is required when --failure-dir is not used"
    [[ -n "${HOST}" ]] || die "--host is required when --failure-dir is not used"
    [[ -n "${FAILURE_ID}" ]] || die "--failure-id is required when --failure-dir is not used"
    FAILURE_DIR="${RAW_DATA_ROOT}/${HOST}/failure/${FAILURE_ID}"
    [[ -d "${FAILURE_DIR}" ]] || die "Failure directory does not exist: ${FAILURE_DIR}"
fi

META_TESTING_MODE=""
if [[ -n "${RAW_DATA_ROOT}" && -n "${HOST}" ]]; then
    META_PATH="${RAW_DATA_ROOT}/${HOST}/runner_result/metadata.env"
    if [[ -f "${META_PATH}" ]]; then
        if [[ -z "${SYSTEM}" ]]; then
            SYSTEM="$(awk -F= '/^SYSTEM=/{print $2}' "${META_PATH}" | tail -n1)"
        fi
        if [[ -z "${ORIGINAL_VERSION}" ]]; then
            ORIGINAL_VERSION="$(awk -F= '/^ORIGINAL_VERSION=/{print $2}' "${META_PATH}" | tail -n1)"
        fi
        if [[ -z "${UPGRADED_VERSION}" ]]; then
            UPGRADED_VERSION="$(awk -F= '/^UPGRADED_VERSION=/{print $2}' "${META_PATH}" | tail -n1)"
        fi
        META_TESTING_MODE="$(awk -F= '/^TESTING_MODE=/{print $2}' "${META_PATH}" | tail -n1)"
    fi
fi

[[ -n "${SYSTEM}" ]] || die "Missing system; pass --system or provide metadata via --raw-data-root/--host"
[[ -n "${ORIGINAL_VERSION}" ]] || die "Missing original version"
[[ -n "${UPGRADED_VERSION}" ]] || die "Missing upgraded version"
if [[ "${TESTING_MODE_EXPLICIT}" != true ]]; then
    # Reproducer should execute the exact test plan parsed from failure artifacts.
    # That path is testing mode 3 (example test plan packet).
    TESTING_MODE=3
fi
[[ "${TESTING_MODE}" =~ ^[0-9]+$ ]] || die "Invalid testing mode: ${TESTING_MODE}"
[[ "${COMMAND_NODE_SEED}" =~ ^[0-9]+$ ]] || die "Invalid --command-node-seed: ${COMMAND_NODE_SEED}"
case "${COMMAND_NODE_STRATEGY}" in
    preserve|zero|round_robin|random) ;;
    *)
        die "Invalid --command-node-strategy: ${COMMAND_NODE_STRATEGY}"
        ;;
esac

case "${SYSTEM}" in
    cassandra|hdfs|hbase) ;;
    *) die "Unsupported system: ${SYSTEM}" ;;
esac

source_report=""
fallback_report=""
while IFS= read -r cand; do
    [[ -n "${cand}" ]] || continue
    fallback_report="${cand}"
    if rg -q '^test plan:' "${cand}"; then
        source_report="${cand}"
    fi
done < <(find "${FAILURE_DIR}" -type f \( -name 'inconsistency_*.report' -o -name 'inconsistency_crosscluster_*.report' -o -name 'error_*.report' -o -name 'error_log_*.report' -o -name 'event_crash_*.report' \) | sort -V)

if [[ -z "${source_report}" ]]; then
    while IFS= read -r cand; do
        [[ -n "${cand}" ]] || continue
        fallback_report="${cand}"
        if rg -q '^test plan:' "${cand}"; then
            source_report="${cand}"
        fi
    done < <(find "${FAILURE_DIR}" -maxdepth 1 -type f -name 'fullSequence_*.report' | sort -V)
fi

if [[ -z "${source_report}" ]]; then
    source_report="${fallback_report}"
fi
[[ -n "${source_report}" ]] || die "No parseable report found in ${FAILURE_DIR} (expected inconsistency_*.report, error_*.report, or fullSequence_*.report)"

failure_base="$(basename "${FAILURE_DIR}")"
FIXED_CONFIG_IDX="$(rg -m1 -o 'ConfigIdx = test[0-9]+' "${FAILURE_DIR}" 2>/dev/null | sed -E 's/.*test([0-9]+)/\1/' | head -n1 || true)"
if [[ -z "${FIXED_CONFIG_IDX}" ]]; then
    fid_num="$(echo "${failure_base}" | sed -E 's/[^0-9]//g')"
    if [[ -n "${fid_num}" ]]; then
        FIXED_CONFIG_IDX="$((900000 + fid_num))"
    else
        FIXED_CONFIG_IDX="999999"
    fi
fi

NODE_NUM_FROM_REPORT="$(awk -F= '/^nodeNum[[:space:]]*=/{gsub(/[[:space:]]/, "", $2); print $2; exit}' "${source_report}" || true)"
if [[ -n "${NODE_NUM_FROM_REPORT}" ]] && [[ ! "${NODE_NUM_FROM_REPORT}" =~ ^[0-9]+$ ]]; then
    die "Invalid nodeNum parsed from report: ${NODE_NUM_FROM_REPORT}"
fi
NODE_NUM_FOR_REWRITE="${NODE_NUM_FROM_REPORT:-3}"
if [[ ! "${NODE_NUM_FOR_REWRITE}" =~ ^[0-9]+$ ]] || (( NODE_NUM_FOR_REWRITE <= 0 )); then
    NODE_NUM_FOR_REWRITE=3
fi

testplan_tmp="$(mktemp)"
valid_tmp="$(mktemp)"
testplan_effective_tmp="$(mktemp)"

awk '
    BEGIN {in_plan=0}
    /^test plan:/ {in_plan=1; next}
    /^test plan end$/ {in_plan=0; next}
    in_plan==1 {print}
' "${source_report}" > "${testplan_tmp}"

awk '
    BEGIN {in_valid=0}
    /^validation commands:/ {in_valid=1; next}
    in_valid==1 {print}
' "${source_report}" | sed '/^[[:space:]]*$/d' > "${valid_tmp}"

[[ -s "${testplan_tmp}" ]] || die "Parsed test plan is empty from ${source_report}"

has_command_node_identity=false
if rg -q '^\[Command\]\[Node\[[0-9]+\]\] Execute \{' "${testplan_tmp}"; then
    has_command_node_identity=true
fi

case "${COMMAND_NODE_STRATEGY}" in
    preserve)
        cp -f "${testplan_tmp}" "${testplan_effective_tmp}"
        ;;
    zero|round_robin|random)
        if [[ "${has_command_node_identity}" == true ]]; then
            cp -f "${testplan_tmp}" "${testplan_effective_tmp}"
            log "test plan already contains command node identity; strategy ${COMMAND_NODE_STRATEGY} is ignored"
        else
            rewrite_legacy_command_nodes "${testplan_tmp}" "${testplan_effective_tmp}" \
                "${COMMAND_NODE_STRATEGY}" "${NODE_NUM_FOR_REWRITE}" "${COMMAND_NODE_SEED}"
        fi
        ;;
esac

example_dir="${ROOT_DIR}/examplecase"
mkdir -p "${example_dir}"

if [[ "${SYSTEM}" == "hdfs" ]]; then
    testplan_dst="${example_dir}/testplan_hdfs_example.txt"
    valid_dst="${example_dir}/validcommands_hdfs_example.txt"
else
    testplan_dst="${example_dir}/testplan.txt"
    valid_dst="${example_dir}/validcommands.txt"
fi

cp -f "${testplan_effective_tmp}" "${testplan_dst}"
cp -f "${valid_tmp}" "${valid_dst}"
rm -f "${testplan_tmp}" "${testplan_effective_tmp}" "${valid_tmp}"

oracle_default_dst="${example_dir}/validation_oracle.txt"
oracle_hdfs_dst="${example_dir}/validation_oracle_hdfs_example.txt"
rm -f "${oracle_default_dst}" "${oracle_hdfs_dst}"

oracle_dst="${oracle_default_dst}"
if [[ "${SYSTEM}" == "hdfs" ]]; then
    oracle_dst="${oracle_hdfs_dst}"
fi

oracle_source_report=""
if [[ "$(basename "${source_report}")" == inconsistency_*.report ]] || \
   [[ "$(basename "${source_report}")" == inconsistency_crosscluster_*.report ]]; then
    oracle_source_report="${source_report}"
else
    while IFS= read -r cand; do
        [[ -n "${cand}" ]] || continue
        if rg -q 'Result inconsistency at read id:' "${cand}" || \
           rg -q 'Insignificant Result inconsistency at read id:' "${cand}" || \
           rg -q 'Cross-cluster inconsistency detected' "${cand}" || \
           rg -q 'Structured validation divergence' "${cand}"; then
            oracle_source_report="${cand}"
            break
        fi
    done < <(find "${FAILURE_DIR}/inconsistency" -maxdepth 1 -type f \( -name 'inconsistency_*.report' -o -name 'inconsistency_crosscluster_*.report' \) 2>/dev/null | sort -V)
fi

oracle_entries=0
if [[ -n "${oracle_source_report}" && -f "${oracle_source_report}" ]]; then
    oracle_entries="$(build_oracle_from_inconsistency_report "${oracle_source_report}" "${oracle_dst}")"
    if [[ "${oracle_entries}" -eq 0 ]]; then
        rm -f "${oracle_dst}"
    fi
fi

if [[ ! -s "${valid_dst}" ]]; then
    log "validation commands are empty in raw report; replay will continue with an empty validation file"
fi

config_pair_dir="${ROOT_DIR}/configtests/${ORIGINAL_VERSION}_${UPGRADED_VERSION}"
replay_config_dir="${config_pair_dir}/test${FIXED_CONFIG_IDX}"
mkdir -p "${config_pair_dir}"

staged_config=false
if [[ -d "${FAILURE_DIR}/oriconfig" ]]; then
    rm -rf "${replay_config_dir}/oriconfig"
    mkdir -p "${replay_config_dir}/oriconfig"
    cp -a "${FAILURE_DIR}/oriconfig/." "${replay_config_dir}/oriconfig/"
    staged_config=true
fi
if [[ -d "${FAILURE_DIR}/upconfig" ]]; then
    rm -rf "${replay_config_dir}/upconfig"
    mkdir -p "${replay_config_dir}/upconfig"
    cp -a "${FAILURE_DIR}/upconfig/." "${replay_config_dir}/upconfig/"
    staged_config=true
fi
if [[ "${staged_config}" != true ]]; then
    log "warning: no oriconfig/upconfig found in failure dir; replay will use existing generated config for test${FIXED_CONFIG_IDX}"
fi

log "Prepared replay artifacts"
log "  failure_dir: ${FAILURE_DIR}"
log "  source_report: ${source_report}"
log "  testplan: ${testplan_dst}"
log "  validcommands: ${valid_dst}"
if [[ -n "${oracle_source_report}" ]]; then
    log "  oracle_source_report: ${oracle_source_report}"
fi
if [[ "${oracle_entries}" -gt 0 ]]; then
    log "  validation_oracle: ${oracle_dst} (entries=${oracle_entries})"
else
    log "  validation_oracle: none"
fi
log "  fixed_config_idx: ${FIXED_CONFIG_IDX}"
log "  command_node_strategy: ${COMMAND_NODE_STRATEGY}"
if [[ "${COMMAND_NODE_STRATEGY}" == "random" ]]; then
    log "  command_node_seed: ${COMMAND_NODE_SEED}"
fi
log "  command_node_identity_present_in_source: ${has_command_node_identity}"
if [[ "${staged_config}" == true ]]; then
    log "  staged_replay_config: ${replay_config_dir}"
fi
if [[ -n "${NODE_NUM_FROM_REPORT}" ]]; then
    log "  node_num_from_report: ${NODE_NUM_FROM_REPORT}"
fi

if [[ -z "${RUN_NAME}" ]]; then
    RUN_NAME="repro_${SYSTEM}_${ORIGINAL_VERSION}_to_${UPGRADED_VERSION}_${failure_base}_$(date '+%Y%m%d_%H%M%S')"
fi
RUN_NAME="${RUN_NAME//[^a-zA-Z0-9._-]/_}"

runner_cmd=(
    "${ROOT_DIR}/scripts/runner/run_rolling_fuzzing.sh"
    --system "${SYSTEM}"
    --original "${ORIGINAL_VERSION}"
    --upgraded "${UPGRADED_VERSION}"
    --testing-mode "${TESTING_MODE}"
    --rounds "${ROUND_NUM}"
    --timeout-sec "${TIMEOUT_SEC}"
    --clients "${CLIENTS}"
    --use-trace "${USE_TRACE}"
    --print-trace "${PRINT_TRACE}"
    --fixed-config-idx "${FIXED_CONFIG_IDX}"
    --run-name "${RUN_NAME}"
)

if [[ -n "${NODE_NUM_FROM_REPORT}" ]]; then
    runner_cmd+=(--node-num "${NODE_NUM_FROM_REPORT}")
fi

if [[ "${SKIP_PRE_CLEAN}" == true ]]; then
    runner_cmd+=(--skip-pre-clean)
fi

log "Launching reproduction run"
if [[ -n "${META_TESTING_MODE}" ]]; then
    log "  original_metadata_testing_mode: ${META_TESTING_MODE}"
fi
log "  testing_mode: ${TESTING_MODE}"
if [[ -n "${FIXED_CONFIG_IDX}" ]]; then
    log "  fixed_config_idx: ${FIXED_CONFIG_IDX}"
fi
log "  command: ${runner_cmd[*]}"
(
    cd "${ROOT_DIR}"
    "${runner_cmd[@]}"
)

results_dir="${ROOT_DIR}/scripts/runner/results/${RUN_NAME}"
summary="${results_dir}/summary.txt"

log "Reproduction completed"
log "  results_dir: ${results_dir}"
if [[ -f "${summary}" ]]; then
    log "  summary: ${summary}"
    rg -n '^run_name:|^system:|^original_version:|^upgraded_version:|^observed_rounds:|^new_failure_dirs:|^stop_reason:|^trace_signal_ok:' "${summary}" || true
fi
