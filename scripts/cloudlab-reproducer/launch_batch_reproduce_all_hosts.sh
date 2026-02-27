#!/usr/bin/env bash
set -euo pipefail

RAW_LOCAL_ROOT="/home/shuai/xlab/rupfuzz/cloudlab-results/feb26/raw_data"
HOSTS=(
  c220g5-110417.wisc.cloudlab.us
  c220g5-110402.wisc.cloudlab.us
  c220g5-111224.wisc.cloudlab.us
  c220g5-111228.wisc.cloudlab.us
  c220g5-111230.wisc.cloudlab.us
  c220g5-111226.wisc.cloudlab.us
)

REMOTE_BASE="/users/swang516/xlab/rupfuzz"
REMOTE_REPO="${REMOTE_BASE}/upfuzz"
REMOTE_RAW_ROOT="${REMOTE_BASE}/cloudlab-results/feb26/raw_data"
REMOTE_OUT_ROOT="${REMOTE_BASE}/cloudlab-results/feb26/analyze_data/repro_all"

PER_FAILURE_TIMEOUT_SEC="${PER_FAILURE_TIMEOUT_SEC:-360}"
RUNNER_TIMEOUT_SEC="${RUNNER_TIMEOUT_SEC:-360}"

log() {
  printf '[%s] %s\n' "$(date '+%F %T')" "$*"
}

for host in "${HOSTS[@]}"; do
  log "Sync script + raw_data to ${host}"
  ssh -o StrictHostKeyChecking=no swang516@"${host}" "mkdir -p ${REMOTE_REPO}/scripts/cloudlab-reproducer ${REMOTE_RAW_ROOT}/${host} ${REMOTE_OUT_ROOT}/${host}"
  scp -o StrictHostKeyChecking=no \
    /home/shuai/xlab/rupfuzz/upfuzz-shuai/scripts/cloudlab-reproducer/run_reproduce_from_raw_data.sh \
    /home/shuai/xlab/rupfuzz/upfuzz-shuai/scripts/cloudlab-reproducer/batch_reproduce_failures_on_host.sh \
    swang516@"${host}":"${REMOTE_REPO}/scripts/cloudlab-reproducer/"
  rsync -az --delete -e "ssh -o StrictHostKeyChecking=no" \
    "${RAW_LOCAL_ROOT}/${host}/" \
    "swang516@${host}:${REMOTE_RAW_ROOT}/${host}/"
done

for host in "${HOSTS[@]}"; do
  log "Launch remote batch on ${host}"
  ssh -o StrictHostKeyChecking=no swang516@"${host}" "bash -lc 'cd ${REMOTE_REPO} && nohup scripts/cloudlab-reproducer/batch_reproduce_failures_on_host.sh --host ${host} --repo-root ${REMOTE_REPO} --raw-data-root ${REMOTE_RAW_ROOT} --out-root ${REMOTE_OUT_ROOT} --per-failure-timeout-sec ${PER_FAILURE_TIMEOUT_SEC} --runner-timeout-sec ${RUNNER_TIMEOUT_SEC} > /tmp/repro_all_${host}.log 2>&1 & echo \$! > /tmp/repro_all_${host}.pid'"
done

log "All host jobs launched."
for host in "${HOSTS[@]}"; do
  log "${host}: pid=$(ssh -o StrictHostKeyChecking=no swang516@"${host}" "cat /tmp/repro_all_${host}.pid 2>/dev/null || echo missing")"
done
