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
DISPATCH_TSV=""
OUTPUT_TSV=""

usage() {
    cat <<EOF
Usage:
  $(basename "$0") [options]

Options:
  --machine-list <path>   Machine list file (default: ${DEFAULT_MACHINE_LIST})
  --results-root <path>   cloudlab-runner results root (default: ${DEFAULT_RESULTS_ROOT})
  --dispatch-tsv <path>   Explicit dispatch.tsv to use
  --remote-repo <path>    Remote upfuzz repo path (default: ${DEFAULT_REMOTE_REPO})
  --output-tsv <path>     Save raw per-machine TSV to this path
  -h, --help              Show this help

The script prints per-machine status including:
  - diff feedback packet count and whether it matches executions
  - lane-feedback pass/fail (collection/runtime status) for old-old/rolling/new-new
  - lane-error-signal pass/fail (from [Only Old]/[Rolling]/[Only New] ERROR reports)
  - bucket counts from verdict counters (candidate/same_version/noise)
  - filesystem delta bucket counts since this run started
EOF
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

latest_dispatch_tsv() {
    local root="$1"
    local p
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
        --remote-repo)
            REMOTE_REPO="$2"
            shift 2
            ;;
        --output-tsv)
            OUTPUT_TSV="$2"
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

[[ -f "${MACHINE_LIST}" ]] || die "Machine list not found: ${MACHINE_LIST}"
if [[ -z "${DISPATCH_TSV}" ]]; then
    DISPATCH_TSV="$(latest_dispatch_tsv "${RESULTS_ROOT}")" || die "Cannot find dispatch.tsv under ${RESULTS_ROOT}"
fi
[[ -f "${DISPATCH_TSV}" ]] || die "Dispatch TSV not found: ${DISPATCH_TSV}"

if [[ -z "${OUTPUT_TSV}" ]]; then
    OUTPUT_TSV="${SCRIPT_DIR}/results/monitor_status_$(date '+%Y%m%d_%H%M%S').tsv"
fi
mkdir -p "$(dirname "${OUTPUT_TSV}")"
DATA_TSV="${OUTPUT_TSV}.data"

declare -A HOST_TO_RUN
while IFS=$'\t' read -r job_id machine_line run_name status message; do
    [[ -z "${job_id}" || "${job_id}" == "job_id" ]] && continue
    host_token="$(echo "${machine_line}" | awk '{print $NF}')"
    host_no_user="${host_token#*@}"
    HOST_TO_RUN["${host_token}"]="${run_name}"
    HOST_TO_RUN["${host_no_user}"]="${run_name}"
done < "${DISPATCH_TSV}"

declare -a MACHINE_LINES=()
while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ -z "${line// }" || "${line}" =~ ^[[:space:]]*# ]] && continue
    MACHINE_LINES+=("${line}")
done < "${MACHINE_LIST}"

[[ "${#MACHINE_LINES[@]}" -gt 0 ]] || die "No usable machine lines in ${MACHINE_LIST}"

: > "${DATA_TSV}"

run_one_machine() {
    local machine_line="$1"
    local ssh_cmd=()
    local host_token=""
    local host_no_user=""
    local run_name=""

    if [[ "${machine_line}" == ssh* ]]; then
        read -r -a ssh_cmd <<< "${machine_line}"
        host_token="${ssh_cmd[$((${#ssh_cmd[@]} - 1))]}"
    else
        host_token="${machine_line}"
        ssh_cmd=(ssh "${machine_line}")
    fi
    host_no_user="${host_token#*@}"
    run_name="${HOST_TO_RUN[${host_token}]:-${HOST_TO_RUN[${host_no_user}]:-}}"

    local tmpf
    tmpf="$(mktemp)"

    if "${ssh_cmd[@]}" "bash -s -- '$(printf "%q" "${REMOTE_REPO}")' '$(printf "%q" "${run_name}")'" > "${tmpf}" 2>&1 <<'REMOTE'
set -euo pipefail

repo="$1"
run_name_hint="$2"

resolve_run_name() {
    local hint="$1"
    local cloudlab_results="$2"
    if [[ -n "${hint}" ]]; then
        echo "${hint}"
        return 0
    fi
    local latest
    latest="$(ls -1dt "${cloudlab_results}"/* 2>/dev/null | rg -v '/distributed_' | head -n1 || true)"
    if [[ -n "${latest}" ]]; then
        basename "${latest}"
        return 0
    fi
    return 1
}

cloudlab_results="${repo}/scripts/cloudlab-runner/results"
run_name="$(resolve_run_name "${run_name_hint}" "${cloudlab_results}" || true)"
if [[ -z "${run_name}" ]]; then
    echo "ERROR_NO_RUN"
    exit 0
fi

run_dir="${repo}/scripts/runner/results/${run_name}"
cloudlab_dir="${cloudlab_results}/${run_name}"
monitor_log="${run_dir}/monitor.log"
server_stdout="${run_dir}/server_stdout.log"
summary_file="${cloudlab_dir}/summary.txt"
tmux_name="upfuzz_${run_name//./_}"

state="NOT_FOUND"
if tmux has-session -t "${tmux_name}" 2>/dev/null; then
    state="RUNNING"
elif [[ -f "${summary_file}" ]]; then
    state="COMPLETED"
elif [[ -f "${monitor_log}" || -f "${server_stdout}" ]]; then
    state="STARTING"
fi

executions=0
diff_feedback_packets=0
monitor_server_alive=-1
run_start_ts=""

if [[ -f "${monitor_log}" ]]; then
    run_start_ts="$(awk -F',' 'NR>1 && NF>=5 && $2 ~ /^[0-9]+$/ {print $1; exit}' "${monitor_log}" || true)"
    last_line="$(awk -F',' 'NF>=5 && $2 ~ /^[0-9]+$/ {line=$0} END {print line}' "${monitor_log}" || true)"
    if [[ -n "${last_line}" ]]; then
        IFS=',' read -r _ts _elapsed _rounds _diff _alive <<< "${last_line}"
        executions="${_rounds:-0}"
        diff_feedback_packets="${_diff:-0}"
        monitor_server_alive="${_alive:- -1}"
    fi
fi

old_timeout=0
rolling_timeout=0
new_timeout=0
old_fail=0
rolling_fail=0
new_fail=0
old_err_fail=0
rolling_err_fail=0
new_err_fail=0
candidate=0
same_version=0
noise=0

if [[ -f "${server_stdout}" ]]; then
    timeout_line="$(grep -a -E 'lane timeout old/roll/new : [0-9]+/[0-9]+/[0-9]+' "${server_stdout}" | tail -n1 || true)"
    collect_line="$(grep -a -E 'lane collect fail old/roll/new : [0-9]+/[0-9]+/[0-9]+' "${server_stdout}" | tail -n1 || true)"
    verdict_line="$(grep -a -E 'candidates : [0-9]+.*same-version bugs : [0-9]+.*noise : [0-9]+' "${server_stdout}" | tail -n1 || true)"

    if [[ "${timeout_line}" =~ ([0-9]+)/([0-9]+)/([0-9]+) ]]; then
        old_timeout="${BASH_REMATCH[1]}"
        rolling_timeout="${BASH_REMATCH[2]}"
        new_timeout="${BASH_REMATCH[3]}"
    fi
    if [[ "${collect_line}" =~ ([0-9]+)/([0-9]+)/([0-9]+) ]]; then
        old_fail="${BASH_REMATCH[1]}"
        rolling_fail="${BASH_REMATCH[2]}"
        new_fail="${BASH_REMATCH[3]}"
    fi

    c="$(echo "${verdict_line}" | sed -n 's/.*candidates : \([0-9][0-9]*\).*/\1/p' | head -n1 || true)"
    s="$(echo "${verdict_line}" | sed -n 's/.*same-version bugs : \([0-9][0-9]*\).*/\1/p' | head -n1 || true)"
    n="$(echo "${verdict_line}" | sed -n 's/.*noise : \([0-9][0-9]*\).*/\1/p' | head -n1 || true)"
    [[ -n "${c}" ]] && candidate="${c}"
    [[ -n "${s}" ]] && same_version="${s}"
    [[ -n "${n}" ]] && noise="${n}"
fi

count_lane_error_reports_since_run() {
    local lane_tag="$1"
    local start_ts="$2"
    local total=0
    local line=""
    local dir=""
    local -a time_filter=()
    if [[ -n "${start_ts}" ]]; then
        time_filter=(-newermt "${start_ts}")
    fi
    for dir in \
        "${repo}/failure/candidate" \
        "${repo}/failure/same_version" \
        "${repo}/failure/noise"; do
        [[ -d "${dir}" ]] || continue
        while IFS= read -r -d '' line; do
            case "${line}" in
                "[${lane_tag}] [ERROR LOG]"*)
                    total=$((total + 1))
                    ;;
            esac
        done < <(
            find "${dir}" \
                -type f \( -path '*/errorLog/error_*.report' -o -path '*/errorLog/error_log_*.report' \) \
                "${time_filter[@]}" \
                -exec head -n1 {} \; 2>/dev/null \
                | tr -d '\r' \
                | tr '\n' '\0'
        )
    done
    echo "${total}"
}

old_err_fail="$(count_lane_error_reports_since_run "Only Old" "${run_start_ts}")"
rolling_err_fail="$(count_lane_error_reports_since_run "Rolling" "${run_start_ts}")"
new_err_fail="$(count_lane_error_reports_since_run "Only New" "${run_start_ts}")"

if [[ -f "${summary_file}" ]]; then
    s_exec="$(sed -n 's/^observed_rounds: //p' "${summary_file}" | head -n1 || true)"
    s_diff="$(sed -n 's/^diff_feedback_packets: //p' "${summary_file}" | head -n1 || true)"
    [[ -n "${s_exec}" ]] && executions="${s_exec}"
    [[ -n "${s_diff}" ]] && diff_feedback_packets="${s_diff}"
fi

if (( executions < old_fail )); then old_fail="${executions}"; fi
if (( executions < rolling_fail )); then rolling_fail="${executions}"; fi
if (( executions < new_fail )); then new_fail="${executions}"; fi
if (( executions < old_err_fail )); then old_err_fail="${executions}"; fi
if (( executions < rolling_err_fail )); then rolling_err_fail="${executions}"; fi
if (( executions < new_err_fail )); then new_err_fail="${executions}"; fi

old_pass=$((executions - old_fail))
rolling_pass=$((executions - rolling_fail))
new_pass=$((executions - new_fail))
old_err_pass=$((executions - old_err_fail))
rolling_err_pass=$((executions - rolling_err_fail))
new_err_pass=$((executions - new_err_fail))
if (( old_pass < 0 )); then old_pass=0; fi
if (( rolling_pass < 0 )); then rolling_pass=0; fi
if (( new_pass < 0 )); then new_pass=0; fi
if (( old_err_pass < 0 )); then old_err_pass=0; fi
if (( rolling_err_pass < 0 )); then rolling_err_pass=0; fi
if (( new_err_pass < 0 )); then new_err_pass=0; fi

diff_ok="NO"
if (( diff_feedback_packets == executions )); then
    diff_ok="YES"
fi

# Filesystem delta since this run started (non-cumulative if run_start_ts is available)
delta_candidate=0
delta_same_version=0
delta_noise=0

count_bucket_delta() {
    local bucket="$1"
    local start_ts="$2"
    local dir="${repo}/failure/${bucket}"
    if [[ -z "${start_ts}" || ! -d "${dir}" ]]; then
        echo 0
        return
    fi
    find "${dir}" -mindepth 1 -maxdepth 1 -type d -name 'failure_*' -newermt "${start_ts}" 2>/dev/null | wc -l | tr -d ' '
}

delta_candidate="$(count_bucket_delta candidate "${run_start_ts}")"
delta_same_version="$(count_bucket_delta same_version "${run_start_ts}")"
delta_noise="$(count_bucket_delta noise "${run_start_ts}")"

printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${run_name}" "${state}" \
    "${executions}" "${diff_feedback_packets}" "${diff_ok}" \
    "3" \
    "${old_pass}" "${old_fail}" \
    "${rolling_pass}" "${rolling_fail}" \
    "${new_pass}" "${new_fail}" \
    "${old_err_pass}" "${old_err_fail}" \
    "${rolling_err_pass}" "${rolling_err_fail}" \
    "${new_err_pass}" "${new_err_fail}" \
    "${old_timeout}" "${rolling_timeout}" "${new_timeout}" \
    "${candidate}" "${same_version}" "${noise}" \
    "${delta_candidate}" "${delta_same_version}" "${delta_noise}" \
    "${run_start_ts}" "${monitor_server_alive}" "${run_dir}"
REMOTE
    then
        remote_line="$(tail -n1 "${tmpf}")"
        if [[ "${remote_line}" == "ERROR_NO_RUN" ]]; then
            printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
                "${host_token}" "-" "-" "NO_RUN" \
                "0" "0" "NO" "3" \
                "0" "0" "0" "0" "0" "0" \
                "0" "0" "0" "0" "0" "0" \
                "0" "0" "0" \
                "0" "0" "0" \
                "0" "0" "0" \
                "" "-1" "" >> "${DATA_TSV}"
        else
            printf "%s\t%s\t%s\t%s\n" \
                "${host_token}" "-" "-" "${remote_line}" >> "${DATA_TSV}"
        fi
    else
        err="$(tr '\n' ';' < "${tmpf}")"
        printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
            "${host_token}" "-" "-" "SSH_FAIL" \
            "0" "0" "NO" "3" \
            "0" "0" "0" "0" "0" "0" \
            "0" "0" "0" "0" "0" "0" \
            "0" "0" "0" \
            "0" "0" "0" \
            "0" "0" "0" \
            "" "-1" "${err}" >> "${DATA_TSV}"
    fi
    rm -f "${tmpf}"
}

for line in "${MACHINE_LINES[@]}"; do
    run_one_machine "${line}" &
done
wait

sort -t $'\t' -k1,1 "${DATA_TSV}" -o "${DATA_TSV}"

{
    echo -e "machine\tjob_id\tdispatch_status\trun_name\tstate\texecutions\tdiff_feedback_packets\tdiff_ok\tlane_count\told_old_pass\told_old_fail\trolling_pass\trolling_fail\tnew_new_pass\tnew_new_fail\told_old_err_pass\told_old_err_fail\trolling_err_pass\trolling_err_fail\tnew_new_err_pass\tnew_new_err_fail\told_old_timeout\trolling_timeout\tnew_new_timeout\tcandidate\tsame_version\tnoise\tdelta_candidate\tdelta_same_version\tdelta_noise\trun_start_ts\tmonitor_server_alive\trun_dir"
    cat "${DATA_TSV}"
} > "${OUTPUT_TSV}"

printf "dispatch: %s\n" "${DISPATCH_TSV}"
printf "machine_list: %s\n" "${MACHINE_LIST}"
printf "remote_repo: %s\n" "${REMOTE_REPO}"
printf "raw_tsv: %s\n\n" "${OUTPUT_TSV}"

printf "%-34s %-9s %7s %7s %8s %9s %9s %9s %9s %9s %9s %9s %9s %9s %9s %9s %9s %9s %9s\n" \
    "machine" "state" "exec" "fb_pass" "diff_ok" \
    "fb_old_f" "fb_roll_f" "fb_new_f" \
    "err_old_f" "err_roll_f" "err_new_f" \
    "candidate" "same_ver" "noise" \
    "delta_cand" "delta_same" "delta_noise" \
    "to_roll" "alive"

while IFS=$'\t' read -r host _j1 _j2 run_name state executions diff_feedback diff_ok lanes \
    old_pass old_fail rolling_pass rolling_fail new_pass new_fail \
    old_err_pass old_err_fail rolling_err_pass rolling_err_fail new_err_pass new_err_fail \
    old_timeout rolling_timeout new_timeout \
    candidate same_version noise \
    delta_candidate delta_same delta_noise run_start_ts monitor_alive run_dir; do
    printf "%-34s %-9s %7s %7s %8s %9s %9s %9s %9s %9s %9s %9s %9s %9s %9s %9s %9s %9s %9s\n" \
        "${host#*@}" "${state}" "${executions}" "${diff_feedback}" "${diff_ok}" \
        "${old_fail}" "${rolling_fail}" "${new_fail}" \
        "${old_err_fail}" "${rolling_err_fail}" "${new_err_fail}" \
        "${candidate}" "${same_version}" "${noise}" \
        "${delta_candidate}" "${delta_same}" "${delta_noise}" \
        "${rolling_timeout}" "${monitor_alive}"
done < "${DATA_TSV}"

total_pass="$(awk -F $'\t' '{s+=$7} END{print s+0}' "${DATA_TSV}")"
total_exec="$(awk -F $'\t' '{s+=$6} END{print s+0}' "${DATA_TSV}")"
total_fb_old_fail="$(awk -F $'\t' '{s+=$11} END{print s+0}' "${DATA_TSV}")"
total_fb_roll_fail="$(awk -F $'\t' '{s+=$13} END{print s+0}' "${DATA_TSV}")"
total_fb_new_fail="$(awk -F $'\t' '{s+=$15} END{print s+0}' "${DATA_TSV}")"
total_err_old_fail="$(awk -F $'\t' '{s+=$17} END{print s+0}' "${DATA_TSV}")"
total_err_roll_fail="$(awk -F $'\t' '{s+=$19} END{print s+0}' "${DATA_TSV}")"
total_err_new_fail="$(awk -F $'\t' '{s+=$21} END{print s+0}' "${DATA_TSV}")"
total_cand="$(awk -F $'\t' '{s+=$25} END{print s+0}' "${DATA_TSV}")"
total_same="$(awk -F $'\t' '{s+=$26} END{print s+0}' "${DATA_TSV}")"
total_noise="$(awk -F $'\t' '{s+=$27} END{print s+0}' "${DATA_TSV}")"
total_delta_cand="$(awk -F $'\t' '{s+=$28} END{print s+0}' "${DATA_TSV}")"
total_delta_same="$(awk -F $'\t' '{s+=$29} END{print s+0}' "${DATA_TSV}")"
total_delta_noise="$(awk -F $'\t' '{s+=$30} END{print s+0}' "${DATA_TSV}")"

echo
echo "totals: exec=${total_exec} fb_pass=${total_pass} fb_fail(old/roll/new)=${total_fb_old_fail}/${total_fb_roll_fail}/${total_fb_new_fail} err_fail(old/roll/new)=${total_err_old_fail}/${total_err_roll_fail}/${total_err_new_fail} candidate=${total_cand} same_version=${total_same} noise=${total_noise} delta_candidate=${total_delta_cand} delta_same_version=${total_delta_same} delta_noise=${total_delta_noise}"

rm -f "${DATA_TSV}"
