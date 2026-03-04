# [JIRA Draft] HBase 3.0.0-beta-1: Intermittent NPE in `RawAsyncHBaseAdmin.isTableEnabled` (`tableState` null dereference)

## Summary
Under transient control-plane failures, `truncate_preserve` on HBase `3.0.0-beta-1` can intermittently trigger:

- `java.lang.NullPointerException: Cannot invoke "java.util.Optional.isPresent()" because "tableState" is null`
- at `RawAsyncHBaseAdmin.lambda$isTableEnabled$39`

This indicates a null-dereference bug in async table-state callback handling.

## Affects Version/s
- `3.0.0-beta-1`

## Environment
- Rolling-upgrade style scenario (`2.6.4 -> 3.0.0-beta-1`) with transient control-plane instability
- Also observed in standalone docker-compose replay (no Upfuzz server/client runtime)

## Reproduction (Standalone, no Upfuzz runtime)
1. Start cluster from replay state (docker-compose).
2. Execute on upgraded shell:
   - `truncate_preserve "uuid5b0d2dc1a8384316a687a9e9f1a037d4"`
3. Repeat under unstable control-plane windows (intermittent).
4. One captured run shows:
   - `Truncating 'uuid5b0d2dc1a8384316a687a9e9f1a037d4' table (it may take a while):`
   - then:
     - `java.lang.NullPointerException: Cannot invoke "java.util.Optional.isPresent()" because "tableState" is null`
     - `at org.apache.hadoop.hbase.client.RawAsyncHBaseAdmin.lambda$isTableEnabled$39(RawAsyncHBaseAdmin.java:781)`

## Expected Result
Should return bounded failure/success without internal NPE.

## Actual Result
NPE appears in async callback path (`FutureUtils` unexpected error log), indicating null handling bug.

## Suspected Root Cause
In `rel/3.0.0-beta-1`, `isTableEnabled` callback dereferences `tableState` directly:
- `tableState.isPresent() ? tableState.get() : null`

If callback gets `error != null` and `tableState == null`, this path throws NPE.

Code location (3.0.0-beta-1):
- `hbase-client/src/main/java/org/apache/hadoop/hbase/client/RawAsyncHBaseAdmin.java:781`

## Additional Note
Current `master` flow appears null-safe (uses `Optional<TableState>` path).

## Reproducibility Note
Intermittent/state-sensitive in our environment:
- captured once with full stack trace,
- not hit in later high-count recheck loops.

Despite intermittency, the null dereference in source is a concrete correctness bug.

## Attachments to Include
- `repro_uuid5b_n0.out` (full standalone reproduction output)
- `repro_uuid5b_n0_key_lines.txt` (stack highlight)
- source snippets:
  - `RawAsyncHBaseAdmin_rel_3_0_0_beta1_snippet.txt`
  - `RawAsyncHBaseAdmin_master_snippet.txt`
