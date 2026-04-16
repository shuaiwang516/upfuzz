package org.zlab.upfuzz.fuzzingengine.server.observability;

/**
 * Per-round branch novelty source attribution.
 *
 * <p>Recorded once per completed differential round, regardless of
 * whether the round was admitted to the corpus. The counters are
 * round-level — Phase 0 intentionally does not attempt per-window
 * attribution because current coverage is only merged at round end.
 *
 * <p>Counters are split into two axes:
 * <ul>
 *   <li>version (old-version probes vs new-version probes)</li>
 *   <li>source (baseline-only lane vs rolling lane vs both)</li>
 * </ul>
 *
 * <p>The source classification reflects which lane <em>first produced</em>
 * the new probe relative to the corpus aggregate up to this round:
 * <ul>
 *   <li><b>baseline-only</b>: a probe that was new in the baseline lane
 *       but already present in the rolling lane (or not hit at all by
 *       rolling).</li>
 *   <li><b>rolling-only</b>: a probe that was new in the rolling lane
 *       but not in the baseline lane.</li>
 *   <li><b>shared</b>: a probe that was new in both lanes this round.</li>
 * </ul>
 *
 * <p>Offline analysis uses these counters to answer "how much novelty
 * came from rolling-only coverage sources?" — the key Phase 5 metric.
 */
public final class BranchNoveltyRow {
    public final long roundId;
    public final int testPacketId;
    public final int oldVersionBaselineOnlyProbes;
    public final int oldVersionRollingOnlyProbes;
    public final int oldVersionSharedProbes;
    public final int newVersionBaselineOnlyProbes;
    public final int newVersionRollingOnlyProbes;
    public final int newVersionSharedProbes;

    /** Phase 5: per-version novelty source label (ROLLING_ONLY, BASELINE_ONLY, MIXED, SHARED, NONE). */
    public final String oldVersionNoveltySource;
    /** Phase 5: per-version novelty source label. */
    public final String newVersionNoveltySource;
    /** Phase 5: round-level novelty classification for scheduling. */
    public final BranchNoveltyClass branchNoveltyClass;

    /** Phase 0 constructor — derives Phase 5 labels automatically. */
    public BranchNoveltyRow(
            long roundId,
            int testPacketId,
            int oldVersionBaselineOnlyProbes,
            int oldVersionRollingOnlyProbes,
            int oldVersionSharedProbes,
            int newVersionBaselineOnlyProbes,
            int newVersionRollingOnlyProbes,
            int newVersionSharedProbes) {
        this(roundId, testPacketId,
                oldVersionBaselineOnlyProbes, oldVersionRollingOnlyProbes,
                oldVersionSharedProbes,
                newVersionBaselineOnlyProbes, newVersionRollingOnlyProbes,
                newVersionSharedProbes,
                null, null, null);
    }

    public BranchNoveltyRow(
            long roundId,
            int testPacketId,
            int oldVersionBaselineOnlyProbes,
            int oldVersionRollingOnlyProbes,
            int oldVersionSharedProbes,
            int newVersionBaselineOnlyProbes,
            int newVersionRollingOnlyProbes,
            int newVersionSharedProbes,
            String oldVersionNoveltySource,
            String newVersionNoveltySource,
            BranchNoveltyClass branchNoveltyClass) {
        this.roundId = roundId;
        this.testPacketId = testPacketId;
        this.oldVersionBaselineOnlyProbes = oldVersionBaselineOnlyProbes;
        this.oldVersionRollingOnlyProbes = oldVersionRollingOnlyProbes;
        this.oldVersionSharedProbes = oldVersionSharedProbes;
        this.newVersionBaselineOnlyProbes = newVersionBaselineOnlyProbes;
        this.newVersionRollingOnlyProbes = newVersionRollingOnlyProbes;
        this.newVersionSharedProbes = newVersionSharedProbes;
        this.oldVersionNoveltySource = oldVersionNoveltySource != null
                ? oldVersionNoveltySource
                : "NONE";
        this.newVersionNoveltySource = newVersionNoveltySource != null
                ? newVersionNoveltySource
                : "NONE";
        this.branchNoveltyClass = branchNoveltyClass != null
                ? branchNoveltyClass
                : BranchNoveltyClass.NONE;
    }

    public int totalNewProbes() {
        return oldVersionBaselineOnlyProbes + oldVersionRollingOnlyProbes
                + oldVersionSharedProbes + newVersionBaselineOnlyProbes
                + newVersionRollingOnlyProbes + newVersionSharedProbes;
    }

    public static String csvHeader() {
        return String.join(",",
                "round_id",
                "test_packet_id",
                "old_version_baseline_only_probes",
                "old_version_rolling_only_probes",
                "old_version_shared_probes",
                "new_version_baseline_only_probes",
                "new_version_rolling_only_probes",
                "new_version_shared_probes",
                "total_new_probes",
                "old_version_novelty_source",
                "new_version_novelty_source",
                "branch_novelty_class",
                "rolling_only_old_probe_count",
                "rolling_only_new_probe_count",
                "baseline_only_probe_count",
                "shared_probe_count");
    }

    public String toCsvRow() {
        StringBuilder sb = new StringBuilder();
        sb.append(roundId).append(',');
        sb.append(testPacketId).append(',');
        sb.append(oldVersionBaselineOnlyProbes).append(',');
        sb.append(oldVersionRollingOnlyProbes).append(',');
        sb.append(oldVersionSharedProbes).append(',');
        sb.append(newVersionBaselineOnlyProbes).append(',');
        sb.append(newVersionRollingOnlyProbes).append(',');
        sb.append(newVersionSharedProbes).append(',');
        sb.append(totalNewProbes()).append(',');
        sb.append(oldVersionNoveltySource).append(',');
        sb.append(newVersionNoveltySource).append(',');
        sb.append(branchNoveltyClass.name()).append(',');
        sb.append(oldVersionRollingOnlyProbes).append(',');
        sb.append(newVersionRollingOnlyProbes).append(',');
        sb.append(oldVersionBaselineOnlyProbes
                + newVersionBaselineOnlyProbes).append(',');
        sb.append(oldVersionSharedProbes + newVersionSharedProbes);
        return sb.toString();
    }
}
