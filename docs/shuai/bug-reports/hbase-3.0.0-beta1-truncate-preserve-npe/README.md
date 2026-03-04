# HBase 3.0.0-beta-1 `truncate_preserve` NPE (Rolling Upgrade)

## Updated Verdict (2026-03-02 / 2026-03-03 Recheck)
- There is a **real HBase bug** in `hbase-client-3.0.0-beta-1`: nullable `tableState` dereference in `RawAsyncHBaseAdmin.isTableEnabled`.
- The specific `test148` timeout in Upfuzz is **not solely a proof of HBase rolling-upgrade failure**. It is strongly amplified by Upfuzz shell-daemon prompt handling when control-plane RPCs fail (`Failed contacting masters`).

## Reproducibility Status
- Observed NPE on CloudLab `c220g5-110432` at `2026-03-03 01:24:51` (CST) with stack:
  - `java.lang.NullPointerException: Cannot invoke "java.util.Optional.isPresent()" because "tableState" is null`
  - `RawAsyncHBaseAdmin.lambda$isTableEnabled$39(RawAsyncHBaseAdmin.java:781)`
- Recheck on `2026-03-02 19:38-20:09`:
  - 12 instability-injection attempts: `NPE_NOT_HIT`
  - 50 repeated direct `truncate_preserve` attempts: `NPE_NOT_HIT`

So this HBase NPE is currently **intermittent/state-sensitive**, not single-shot deterministic in this environment.

## Root Cause (HBase Code Bug)
In `rel/3.0.0-beta-1`, callback code dereferences nullable `tableState`:
- `tableState.isPresent() ? tableState.get() : null`

When callback arrives with `error != null` and `tableState == null`, this causes NPE.

Relevant snippets:
- `artifacts/RawAsyncHBaseAdmin_rel_3_0_0_beta1_snippet.txt`
- `artifacts/RawAsyncHBaseAdmin_master_snippet.txt`

## Why `test148` Timed Out In Upfuzz
`test148` shows repeated control-plane failures (`Failed contacting masters`) before stall.  
In Upfuzz `hbase_daemon3.py`, the read loop waits forever for a prompt and does not check child-process death in the `len(newline)==0` path, so command handling can stall when shell output/prompt behavior diverges under errors.

Evidence:
- `artifacts/recheck/test148_node1_daemon_window.log`
- `artifacts/recheck/standalone_recheck_npe_loop_trunc_1.out`
- `artifacts/recheck/upfuzz_hbase_daemon_prompt_loop_snippet.txt`

## Bundle Contents
- `repro_standalone_cloudlab.sh`: standalone NPE-hunt script (non-Upfuzz runtime).
- `jira_report.md`: JIRA draft for HBase NPE bug.
- `artifacts/repro_uuid5b_n0.out`: captured run with NPE stack.
- `artifacts/repro_uuid5b_n0_key_lines.txt`: key NPE lines.
- `artifacts/repro_uuid038_n1.out`: control command output.
- `artifacts/upfuzz_test148_window.log`: original failing-window trace.
- `artifacts/RawAsyncHBaseAdmin_rel_3_0_0_beta1_snippet.txt`: buggy code snippet.
- `artifacts/RawAsyncHBaseAdmin_master_snippet.txt`: comparison snippet.
- `artifacts/required_files_manifest.txt`: required replay inputs/tarballs.
- `artifacts/recheck/*`: recheck logs showing intermittent behavior and timeout context.

## Important Note
All standalone steps use original instrumented tarballs + docker-compose and do **not** require running Upfuzz server/client runtime.
