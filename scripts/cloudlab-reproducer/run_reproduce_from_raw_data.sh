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
  --testing-mode <N>         Testing mode for replay (default: metadata TESTING_MODE or 3)
  --use-trace <true|false>   Enable trace during replay (default: ${USE_TRACE})
  --print-trace <true|false> Print trace details (default: ${PRINT_TRACE})
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

if [[ -n "${FAILURE_DIR}" ]]; then
    [[ -d "${FAILURE_DIR}" ]] || die "Failure directory does not exist: ${FAILURE_DIR}"
else
    [[ -n "${RAW_DATA_ROOT}" ]] || die "--raw-data-root is required when --failure-dir is not used"
    [[ -n "${HOST}" ]] || die "--host is required when --failure-dir is not used"
    [[ -n "${FAILURE_ID}" ]] || die "--failure-id is required when --failure-dir is not used"
    FAILURE_DIR="${RAW_DATA_ROOT}/${HOST}/failure/${FAILURE_ID}"
    [[ -d "${FAILURE_DIR}" ]] || die "Failure directory does not exist: ${FAILURE_DIR}"
fi

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
        if [[ -z "${TESTING_MODE}" ]]; then
            TESTING_MODE="$(awk -F= '/^TESTING_MODE=/{print $2}' "${META_PATH}" | tail -n1)"
        fi
    fi
fi

[[ -n "${SYSTEM}" ]] || die "Missing system; pass --system or provide metadata via --raw-data-root/--host"
[[ -n "${ORIGINAL_VERSION}" ]] || die "Missing original version"
[[ -n "${UPGRADED_VERSION}" ]] || die "Missing upgraded version"
if [[ -z "${TESTING_MODE}" ]]; then
    TESTING_MODE=3
fi
[[ "${TESTING_MODE}" =~ ^[0-9]+$ ]] || die "Invalid testing mode: ${TESTING_MODE}"

case "${SYSTEM}" in
    cassandra|hdfs|hbase) ;;
    *) die "Unsupported system: ${SYSTEM}" ;;
esac

full_seq_report="$(find "${FAILURE_DIR}" -maxdepth 1 -type f -name 'fullSequence_*.report' | sort -V | tail -n1 || true)"
[[ -n "${full_seq_report}" ]] || die "No fullSequence_*.report found in ${FAILURE_DIR}"

testplan_tmp="$(mktemp)"
valid_tmp="$(mktemp)"

awk '
    BEGIN {in_plan=0}
    /^test plan:/ {in_plan=1; next}
    /^test plan end$/ {in_plan=0; next}
    in_plan==1 {print}
' "${full_seq_report}" > "${testplan_tmp}"

awk '
    BEGIN {in_valid=0}
    /^validation commands:/ {in_valid=1; next}
    in_valid==1 {print}
' "${full_seq_report}" | sed '/^[[:space:]]*$/d' > "${valid_tmp}"

[[ -s "${testplan_tmp}" ]] || die "Parsed test plan is empty from ${full_seq_report}"
[[ -s "${valid_tmp}" ]] || die "Parsed validation commands are empty from ${full_seq_report}"

example_dir="${ROOT_DIR}/examplecase"
mkdir -p "${example_dir}"

if [[ "${SYSTEM}" == "hdfs" ]]; then
    testplan_dst="${example_dir}/testplan_hdfs_example.txt"
    valid_dst="${example_dir}/validcommands_hdfs_example.txt"
else
    testplan_dst="${example_dir}/testplan.txt"
    valid_dst="${example_dir}/validcommands.txt"
fi

cp -f "${testplan_tmp}" "${testplan_dst}"
cp -f "${valid_tmp}" "${valid_dst}"
rm -f "${testplan_tmp}" "${valid_tmp}"

log "Prepared replay artifacts"
log "  failure_dir: ${FAILURE_DIR}"
log "  full_sequence: ${full_seq_report}"
log "  testplan: ${testplan_dst}"
log "  validcommands: ${valid_dst}"

failure_base="$(basename "${FAILURE_DIR}")"
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
    --run-name "${RUN_NAME}"
)

if [[ "${SKIP_PRE_CLEAN}" == true ]]; then
    runner_cmd+=(--skip-pre-clean)
fi

log "Launching reproduction run"
log "  testing_mode: ${TESTING_MODE}"
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
