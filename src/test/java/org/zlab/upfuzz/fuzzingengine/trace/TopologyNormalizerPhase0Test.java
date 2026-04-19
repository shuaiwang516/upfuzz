package org.zlab.upfuzz.fuzzingengine.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Phase 0 regression tests for the topology-aware endpoint resolution
 * path. These tests pin the contract used by
 * {@code FuzzingServer.countUpgradedBoundaryCrossingsDetailed} so future
 * refactors cannot silently drop a resolution tier and re-introduce the
 * Apr16 "upgraded_boundary_positive_windows = 0" pattern.
 */
class TopologyNormalizerPhase0Test {

    @Test
    void registerNodeBindsEveryAliasToIndex() {
        TopologyNormalizer normalizer = new TopologyNormalizer();
        normalizer.registerNode(1, "datanode", "10.0.0.2", "DC3N1",
                "dn1.internal", "executor-N1");
        TopologySnapshot snapshot = normalizer.snapshot();

        assertEquals(1, snapshot.nodeCount());
        // Every registered alias must resolve to the same node index.
        assertEquals(1, snapshot.resolveEndpoint("10.0.0.2").nodeIndex);
        assertEquals(1, snapshot.resolveEndpoint("DC3N1").nodeIndex);
        assertEquals(1, snapshot.resolveEndpoint("dn1.internal").nodeIndex);
        assertEquals(1, snapshot.resolveEndpoint("executor-N1").nodeIndex);
        // Leading-slash form is canonicalized.
        assertEquals(1, snapshot.resolveEndpoint("/10.0.0.2").nodeIndex);
        // Port suffix is stripped.
        assertEquals(1, snapshot.resolveEndpoint("/10.0.0.2:9042").nodeIndex);
    }

    @Test
    void numericParsingCoversRuntimeNodeIdForm() {
        // The rolling runtime sets NET_TRACE_NODE_ID to "<executor>-N<idx>".
        // Even if a caller forgot to register the raw id, numeric parsing
        // must still resolve it so the server sees a crossing instead of
        // an unresolved endpoint.
        TopologyNormalizer normalizer = new TopologyNormalizer();
        normalizer.registerNode(2, "namenode");
        TopologySnapshot snapshot = normalizer.snapshot();

        ResolvedTraceEndpoint resolved = snapshot
                .resolveEndpoint("SrnNTLLS-N2");
        assertEquals(ResolvedTraceEndpoint.Resolution.INDEX_RESOLVED,
                resolved.resolution);
        assertEquals(2, resolved.nodeIndex);
        assertEquals("namenode", resolved.role);
    }

    @Test
    void unknownNumericIndexIsUnresolved() {
        // Numeric parsing must not invent indices that the topology does
        // not know about — otherwise a stale trace from a larger cluster
        // could leak into boundary corroboration.
        TopologyNormalizer normalizer = new TopologyNormalizer();
        normalizer.registerNode(0, "namenode");
        TopologySnapshot snapshot = normalizer.snapshot();

        ResolvedTraceEndpoint resolved = snapshot.resolveEndpoint("N7");
        assertEquals(ResolvedTraceEndpoint.Resolution.UNRESOLVED,
                resolved.resolution);
        assertEquals(-1, resolved.nodeIndex);
    }

    @Test
    void uniqueRolePromotesToRoleUnique() {
        TopologyNormalizer normalizer = new TopologyNormalizer();
        normalizer.registerNode(0, "datanode");
        normalizer.registerNode(1, "datanode");
        normalizer.registerNode(2, "namenode");
        TopologySnapshot snapshot = normalizer.snapshot();

        ResolvedTraceEndpoint resolved = snapshot
                .resolveEndpoint("namenode");
        assertEquals(ResolvedTraceEndpoint.Resolution.ROLE_UNIQUE,
                resolved.resolution);
        assertEquals(2, resolved.nodeIndex);
        assertTrue(resolved.isCorroborating());
    }

    @Test
    void ambiguousRoleIsDiagnosticOnly() {
        TopologyNormalizer normalizer = new TopologyNormalizer();
        normalizer.registerNode(0, "datanode");
        normalizer.registerNode(1, "datanode");
        normalizer.registerNode(2, "namenode");
        TopologySnapshot snapshot = normalizer.snapshot();

        ResolvedTraceEndpoint resolved = snapshot
                .resolveEndpoint("datanode");
        assertEquals(ResolvedTraceEndpoint.Resolution.ROLE_AMBIGUOUS,
                resolved.resolution);
        assertEquals(-1, resolved.nodeIndex);
        assertFalse(resolved.isCorroborating(),
                "ambiguous role must not corroborate a boundary");
    }

    @Test
    void resolveRoleHandlesAliasFromNormalizedTrace() {
        // When TraceEntry.peerRole is already populated by
        // TopologyNormalizer.normalizeTrace, the server's endpoint
        // resolver calls resolveRole instead of just resolveEndpoint.
        // A unique role must still promote to ROLE_UNIQUE.
        TopologyNormalizer normalizer = new TopologyNormalizer();
        normalizer.registerNode(0, "datanode");
        normalizer.registerNode(1, "datanode");
        normalizer.registerNode(2, "namenode");
        TopologySnapshot snapshot = normalizer.snapshot();

        ResolvedTraceEndpoint resolved = snapshot.resolveRole(
                "some-ephemeral-id", "namenode");
        assertEquals(ResolvedTraceEndpoint.Resolution.ROLE_UNIQUE,
                resolved.resolution);
        assertEquals(2, resolved.nodeIndex);

        ResolvedTraceEndpoint ambiguous = snapshot.resolveRole(
                "some-ephemeral-id", "datanode");
        assertEquals(ResolvedTraceEndpoint.Resolution.ROLE_AMBIGUOUS,
                ambiguous.resolution);
    }

    @Test
    void emptySnapshotAlwaysReturnsUnresolved() {
        TopologySnapshot snapshot = TopologySnapshot.empty();
        assertNotNull(snapshot);
        assertEquals(0, snapshot.nodeCount());
        ResolvedTraceEndpoint resolved = snapshot
                .resolveEndpoint("anything");
        assertEquals(ResolvedTraceEndpoint.Resolution.UNRESOLVED,
                resolved.resolution);
    }

    @Test
    void legacyRegisterMappingStillWorks() {
        // Before Phase 0 the normalizer only exposed registerMapping.
        // That path must keep working so the executor (and any test
        // helpers that predate registerNode) continues to resolve roles
        // for TraceEntry.peerRole — it just does not populate the
        // idToIndex map.
        TopologyNormalizer normalizer = new TopologyNormalizer();
        normalizer.registerMapping("10.0.0.1", "namenode");
        assertEquals("namenode", normalizer.resolve("10.0.0.1"));
        TopologySnapshot snapshot = normalizer.snapshot();
        assertFalse(snapshot.idToIndex().containsKey("10.0.0.1"),
                "legacy registerMapping should not invent an index");
    }
}
