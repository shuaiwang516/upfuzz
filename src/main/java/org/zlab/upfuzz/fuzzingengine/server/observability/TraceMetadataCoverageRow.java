package org.zlab.upfuzz.fuzzingengine.server.observability;

/**
 * Phase 0 trace-metadata coverage row. One row per differential round
 * per lane (old-old / rolling / new-new). Captures how many entries in
 * the lane's merged trace carried the IDs and roles that Phase 1 / 2
 * will rely on:
 *
 * <ul>
 *   <li>{@link #entriesWithLogicalMessageId} — entries whose
 *       {@code logicalMessageId} is non-empty and not {@code "null"}.
 *       This is the primary flow-group key Phase 2 uses.</li>
 *   <li>{@link #entriesWithDeliveryId} — entries whose {@code deliveryId}
 *       is non-empty and not {@code "null"}. Fallback flow key for
 *       receive-side events.</li>
 *   <li>{@link #entriesWithNodeRole} / {@link #entriesWithPeerRole} —
 *       entries whose role field was resolved by the executor's
 *       {@link org.zlab.upfuzz.fuzzingengine.trace.TopologyNormalizer}.
 *       These show how much of the trace can already be read in
 *       role-space without further repair.</li>
 *   <li>{@link #entriesWithMissingPeerId} — entries whose {@code peerId}
 *       is null, empty, or the sentinel string {@code "null"}. Tracked
 *       separately so the absence of a peer id stays visible instead of
 *       hiding inside a silent skip.</li>
 *   <li>{@link #entriesWithRoleAmbiguousPeerId} — entries whose
 *       {@code peerId} was present but only resolved to a shared role
 *       (e.g. Cassandra peer-to-peer). These are diagnostic for Phase 0
 *       and do not corroborate a boundary.</li>
 *   <li>{@link #entriesWithUnresolvedPeerId} — entries whose
 *       {@code peerId} was present and did not resolve even via the
 *       shared-role fallback. A high value explains why boundary
 *       corroboration was previously silent — the raw id never got to
 *       any snapshot tier.</li>
 * </ul>
 *
 * <p>The counters are intentionally per-lane (not per-window) because
 * this row is a gate decision for later phases: "is there enough ID /
 * role coverage to justify flow reconstruction?" Per-window breakdowns
 * already live on {@link WindowTriggerRow} via the boundary-resolution
 * histogram, so the lane-level row stays short.
 */
public final class TraceMetadataCoverageRow {
    public final long round;
    public final int testPacketId;
    public final String laneName;
    public final int totalEntries;
    public final int entriesWithLogicalMessageId;
    public final int entriesWithDeliveryId;
    public final int entriesWithNodeRole;
    public final int entriesWithPeerRole;
    public final int entriesWithMissingPeerId;
    public final int entriesWithRoleAmbiguousPeerId;
    public final int entriesWithUnresolvedPeerId;
    public final int topologyNodeCount;
    public final int topologyIdMappingCount;

    public TraceMetadataCoverageRow(long round, int testPacketId,
            String laneName, int totalEntries,
            int entriesWithLogicalMessageId,
            int entriesWithDeliveryId,
            int entriesWithNodeRole,
            int entriesWithPeerRole,
            int entriesWithMissingPeerId,
            int entriesWithRoleAmbiguousPeerId,
            int entriesWithUnresolvedPeerId,
            int topologyNodeCount,
            int topologyIdMappingCount) {
        this.round = round;
        this.testPacketId = testPacketId;
        this.laneName = laneName == null ? "" : laneName;
        this.totalEntries = totalEntries;
        this.entriesWithLogicalMessageId = entriesWithLogicalMessageId;
        this.entriesWithDeliveryId = entriesWithDeliveryId;
        this.entriesWithNodeRole = entriesWithNodeRole;
        this.entriesWithPeerRole = entriesWithPeerRole;
        this.entriesWithMissingPeerId = entriesWithMissingPeerId;
        this.entriesWithRoleAmbiguousPeerId = entriesWithRoleAmbiguousPeerId;
        this.entriesWithUnresolvedPeerId = entriesWithUnresolvedPeerId;
        this.topologyNodeCount = topologyNodeCount;
        this.topologyIdMappingCount = topologyIdMappingCount;
    }

    public static String csvHeader() {
        return String.join(",",
                "round",
                "test_packet_id",
                "lane_name",
                "total_entries",
                "entries_with_logical_message_id",
                "entries_with_delivery_id",
                "entries_with_node_role",
                "entries_with_peer_role",
                "entries_with_missing_peer_id",
                "entries_with_role_ambiguous_peer_id",
                "entries_with_unresolved_peer_id",
                "topology_node_count",
                "topology_id_mapping_count");
    }

    public String toCsvRow() {
        StringBuilder sb = new StringBuilder();
        sb.append(round).append(',');
        sb.append(testPacketId).append(',');
        sb.append(csvEscape(laneName)).append(',');
        sb.append(totalEntries).append(',');
        sb.append(entriesWithLogicalMessageId).append(',');
        sb.append(entriesWithDeliveryId).append(',');
        sb.append(entriesWithNodeRole).append(',');
        sb.append(entriesWithPeerRole).append(',');
        sb.append(entriesWithMissingPeerId).append(',');
        sb.append(entriesWithRoleAmbiguousPeerId).append(',');
        sb.append(entriesWithUnresolvedPeerId).append(',');
        sb.append(topologyNodeCount).append(',');
        sb.append(topologyIdMappingCount);
        return sb.toString();
    }

    private static String csvEscape(String field) {
        if (field == null) {
            return "";
        }
        if (field.indexOf(',') < 0 && field.indexOf('"') < 0
                && field.indexOf('\n') < 0) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }
}
