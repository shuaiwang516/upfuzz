package org.zlab.upfuzz.fuzzingengine.server.observability;

/**
 * Phase 5 per-round stage-level novelty attribution row.
 *
 * <p>Emitted once per completed differential round when
 * {@code enableStageCoverageSnapshots} is true and the rolling-lane
 * feedback carried at least one stage boundary snapshot. Each row
 * records how many new-version probes (relative to the corpus
 * aggregate) were already present at each captured boundary, so
 * offline analysis can determine when novelty first appeared:
 * before the first upgrade, between upgrades, after finalization,
 * or only at round end.
 */
public final class StageNoveltyRow {
    public final long roundId;
    public final int testPacketId;
    public final int totalNewVersionProbes;
    public final int probesAtFirstUpgrade;
    public final int probesAtLastUpgrade;
    public final int probesAtFinalize;
    public final int upgradeSnapshotCount;

    public StageNoveltyRow(
            long roundId,
            int testPacketId,
            int totalNewVersionProbes,
            int probesAtFirstUpgrade,
            int probesAtLastUpgrade,
            int probesAtFinalize,
            int upgradeSnapshotCount) {
        this.roundId = roundId;
        this.testPacketId = testPacketId;
        this.totalNewVersionProbes = totalNewVersionProbes;
        this.probesAtFirstUpgrade = probesAtFirstUpgrade;
        this.probesAtLastUpgrade = probesAtLastUpgrade;
        this.probesAtFinalize = probesAtFinalize;
        this.upgradeSnapshotCount = upgradeSnapshotCount;
    }

    public static String csvHeader() {
        return String.join(",",
                "round_id",
                "test_packet_id",
                "total_new_version_probes",
                "probes_at_first_upgrade",
                "probes_at_last_upgrade",
                "probes_at_finalize",
                "upgrade_snapshot_count");
    }

    public String toCsvRow() {
        StringBuilder sb = new StringBuilder();
        sb.append(roundId).append(',');
        sb.append(testPacketId).append(',');
        sb.append(totalNewVersionProbes).append(',');
        sb.append(probesAtFirstUpgrade).append(',');
        sb.append(probesAtLastUpgrade).append(',');
        sb.append(probesAtFinalize).append(',');
        sb.append(upgradeSnapshotCount);
        return sb.toString();
    }
}
