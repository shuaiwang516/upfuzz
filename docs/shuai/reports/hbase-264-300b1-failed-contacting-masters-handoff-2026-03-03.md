# HBase 2.6.4 -> 3.0.0-beta-1: "Failed contacting masters" Debug Handoff (2026-03-03)

## 1) Problem We Encountered
During rolling-upgrade differential fuzzing for HBase `hbase-2.6.4 -> hbase-3.0.0-beta-1`, we repeatedly observed:

`ERROR: Failed contacting masters after xxx attempts.`

What we confirmed from logs:
- It can happen before the first upgrade op, during upgrade windows, and after node upgrades/finalize points.
- It is not strictly "rolling-only" in practice. In the captured completed rounds, errors were mostly in `Rolling` and `OnlyNew` lanes (mapped by executor id), not `OnlyOld`.
- It is intermittent and testplan-dependent.
- Many runs still complete (`stop_reason: target_rounds_reached`) with non-zero trace lengths in all three lanes.
- Some runs timeout or exit early, so this signal can correlate with instability but is not always fatal.

Key evidence files (on the Cloudlab machine `c220g5-110432.wisc.cloudlab.us`):
- `/users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/repro_test148_recheck_20260302_2054/client_launcher_stdout.log`
- `/users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/repro_test148_recheck_20260302_2054/upfuzz_client_1.log`
- `/users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/repro_test148_recheck_20260302_2054/server_stdout.log`

## 2) Cloudlab Machine And Reproduction
Machine used for all checks in this debug pass:
- SSH host: `c220g5-110432.wisc.cloudlab.us`
- Login: `swang516`
- Resolved hostname on box: `node3.swang516-292870.uptesting-pg0.wisc.cloudlab.us`
- Repo path on machine: `/users/swang516/xlab/rupfuzz/upfuzz`

### A. Reproduce the known noisy case (`test148`)
This is the main case where we observed many `Failed contacting masters` lines.

```bash
ssh swang516@c220g5-110432.wisc.cloudlab.us
cd /users/swang516/xlab/rupfuzz/upfuzz

scripts/cloudlab-reproducer/run_reproduce_from_raw_data.sh \
  --failure-dir /users/swang516/xlab/rupfuzz/upfuzz/failure/same_version/failure_4 \
  --system hbase \
  --original hbase-2.6.4 \
  --upgraded hbase-3.0.0-beta-1 \
  --rounds 1 \
  --timeout-sec 2400 \
  --run-name repro_test148_recheck_20260302_2054 \
  --skip-pre-clean true
```

Check outputs:

```bash
cd /users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/repro_test148_recheck_20260302_2054

cat summary.txt
rg -n "configPath|Upgrade Node|HBase upgrade finalized|Failed contacting masters|stop_reason|observed_rounds" \
  -S client_launcher_stdout.log
```

Expected pattern:
- `configPath = configtests/hbase-2.6.4_hbase-3.0.0-beta-1/test148`
- Multiple `Failed contacting masters` lines
- `stop_reason: target_rounds_reached`
- `observed_rounds: 1`

### B. Reproduce a control case that may have zero failures (`test232`)
This was observed once with `observed_rounds=1` and `Failed contacting masters` count `0`.

```bash
ssh swang516@c220g5-110432.wisc.cloudlab.us
cd /users/swang516/xlab/rupfuzz/upfuzz

scripts/runner/run_rolling_fuzzing.sh \
  --system hbase \
  --original hbase-2.6.4 \
  --upgraded hbase-3.0.0-beta-1 \
  --rounds 1 \
  --testing-mode 3 \
  --fixed-config-idx 232 \
  --timeout-sec 300 \
  --run-name npe_m3_idx232_recheck
```

Check outputs:

```bash
cd /users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/npe_m3_idx232_recheck

cat summary.txt
rg -n "Failed contacting masters|configPath|stop_reason|observed_rounds" -S client_launcher_stdout.log
```

Expected pattern (intermittent):
- `configPath = configtests/hbase-2.6.4_hbase-3.0.0-beta-1/test232`
- Possible `Failed contacting masters` count `0`
- `stop_reason: target_rounds_reached`

### C. Quick aggregate check across HBase 2.6.4 -> 3.0.0-beta-1 runs

```bash
ssh swang516@c220g5-110432.wisc.cloudlab.us
cd /users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results

for d in */; do
  [ -f "$d/client_launcher_stdout.log" ] || continue
  cfg=$(rg -n "configPath =" -m1 "$d/client_launcher_stdout.log" | sed 's/.*configPath = //')
  echo "$cfg" | rg -q "configtests/hbase-2.6.4_hbase-3.0.0-beta-1/test" || continue
  fc=$(rg -n "Failed contacting masters" -S "$d/client_launcher_stdout.log" | wc -l)
  obs=$(awk -F': ' '/^observed_rounds:/{print $2}' "$d/summary.txt" 2>/dev/null)
  sr=$(awk -F': ' '/^stop_reason:/{print $2}' "$d/summary.txt" 2>/dev/null)
  echo "$d fail_count=$fc observed=${obs:-NA} stop_reason=${sr:-NA}"
done | sort
```

## 3) Notes For Restarting Debug
- Focus first on `test148` (repro-friendly noisy case) and compare with `test232` (cleaner control case).
- When debugging lane behavior, use:
  - `upfuzz_client_1.log` for executor/thread command-level context
  - `server_stdout.log` for lane id mapping (`Only Old`, `Rolling`, `Only New`)
- Useful lane-id anchors in `server_stdout.log`:
  - First `Only Old` trace line contains `nodeId='<oldExecutor>-N...'`
  - First `Rolling` trace line contains `nodeId='<rollingExecutor>-N...'`
  - First `Only New` trace line contains `nodeId='<newExecutor>-N...'`
