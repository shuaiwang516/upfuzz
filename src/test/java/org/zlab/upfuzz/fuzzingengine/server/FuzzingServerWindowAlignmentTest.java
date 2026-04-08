package org.zlab.upfuzz.fuzzingengine.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.zlab.net.tracker.Trace;
import org.zlab.upfuzz.fuzzingengine.trace.TraceWindow;
import org.zlab.upfuzz.fuzzingengine.trace.WindowedTrace;

/**
 * Server-side tests for {@link FuzzingServer#alignWindows} partial alignment
 * logic. Tests exercise the method directly with synthetic WindowedTrace
 * objects — no Docker, no Config, no executor required.
 */
class FuzzingServerWindowAlignmentTest {

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Build a comparable TraceWindow with the given stage key. */
    private static TraceWindow window(String stageId,
            Set<Integer> transitionNodes) {
        return new TraceWindow(
                0, // ordinal (not used in alignment)
                "test", // openReason
                "test", // closeReason
                -1, // closeEventIdx
                stageId,
                TraceWindow.StageKind.POST_STAGE,
                transitionNodes,
                Collections.emptySet(), // rawUpgradedNodeSet
                "test", // rawVersionLayout
                true, // comparableAcrossLanes
                new Trace[] { new Trace() });
    }

    /** Convenience: stage key with a simple integer node set. */
    private static TraceWindow window(String stageId, int... nodes) {
        Set<Integer> nodeSet = new HashSet<>();
        for (int n : nodes) {
            nodeSet.add(n);
        }
        return window(stageId, nodeSet);
    }

    private static WindowedTrace traceOf(TraceWindow... windows) {
        WindowedTrace wt = new WindowedTrace();
        for (TraceWindow w : windows) {
            wt.addWindow(w);
        }
        return wt;
    }

    // ---------------------------------------------------------------
    // 1. Equal window counts — fast path (existing behavior preserved)
    // ---------------------------------------------------------------

    @Test
    void equalCounts_allMatch_returnsAligned() {
        TraceWindow pre = window("PRE_UPGRADE", 0);
        TraceWindow post1 = window("POST_STAGE_1", 0);
        TraceWindow postF = window("POST_FINAL_STAGE", 0, 1);

        WindowedTrace oo = traceOf(pre, post1, postF);
        WindowedTrace ro = traceOf(pre, post1, postF);
        WindowedTrace nn = traceOf(pre, post1, postF);

        List<FuzzingServer.AlignedWindow> result = FuzzingServer
                .alignWindows(oo, ro, nn);

        assertEquals(3, result.size());
        assertSame(pre, result.get(0).oldOld);
        assertSame(post1, result.get(1).rolling);
    }

    // ---------------------------------------------------------------
    // 2. Partial alignment: OO=3, RO=2, NN=3 with matching keys
    // (the common Cassandra 4.1→5.0 / HDFS SNN pattern)
    // ---------------------------------------------------------------

    @Test
    void partialAlignment_rollingMissingOne_matchesTwoWindows() {
        TraceWindow pre = window("PRE_UPGRADE", 0);
        TraceWindow post1 = window("POST_STAGE_1", 0);
        TraceWindow postF = window("POST_FINAL_STAGE", 0, 1);

        // OO and NN have all 3 windows; RO is missing the first (PRE_UPGRADE)
        WindowedTrace oo = traceOf(pre, post1, postF);
        WindowedTrace ro = traceOf(post1, postF); // 2 windows
        WindowedTrace nn = traceOf(pre, post1, postF);

        List<FuzzingServer.AlignedWindow> result = FuzzingServer
                .alignWindows(oo, ro, nn);

        assertEquals(2, result.size());
        // First aligned pair: post1
        assertEquals("POST_STAGE_1",
                result.get(0).oldOld.comparisonStageId);
        assertEquals("POST_STAGE_1",
                result.get(0).rolling.comparisonStageId);
        assertEquals("POST_STAGE_1",
                result.get(0).newNew.comparisonStageId);
        // Second aligned pair: postF
        assertEquals("POST_FINAL_STAGE",
                result.get(1).rolling.comparisonStageId);
    }

    // ---------------------------------------------------------------
    // 3. Front-missing case: OO=2, RO=1, NN=2 where RO only has
    // POST_STAGE_1 → should yield 1 aligned window
    // ---------------------------------------------------------------

    @Test
    void partialAlignment_frontMissing_matchesOneWindow() {
        TraceWindow pre = window("PRE_UPGRADE", 0);
        TraceWindow post1 = window("POST_STAGE_1", 0);

        WindowedTrace oo = traceOf(pre, post1);
        WindowedTrace ro = traceOf(post1); // 1 window, missing PRE_UPGRADE
        WindowedTrace nn = traceOf(pre, post1);

        List<FuzzingServer.AlignedWindow> result = FuzzingServer
                .alignWindows(oo, ro, nn);

        assertEquals(1, result.size());
        assertEquals("POST_STAGE_1",
                result.get(0).rolling.comparisonStageId);
    }

    // ---------------------------------------------------------------
    // 4. Reverse case: rolling has MORE windows than baselines → full abstain
    // ---------------------------------------------------------------

    @Test
    void reverseCase_rollingHasMore_abstains() {
        TraceWindow pre = window("PRE_UPGRADE", 0);
        TraceWindow post1 = window("POST_STAGE_1", 0);
        TraceWindow postF = window("POST_FINAL_STAGE", 0, 1);

        WindowedTrace oo = traceOf(pre, postF); // 2 windows
        WindowedTrace ro = traceOf(pre, post1, postF); // 3 windows
        WindowedTrace nn = traceOf(pre, postF); // 2 windows

        List<FuzzingServer.AlignedWindow> result = FuzzingServer
                .alignWindows(oo, ro, nn);

        assertTrue(result.isEmpty(),
                "Should abstain when rolling has more windows than baselines");
    }

    // ---------------------------------------------------------------
    // 5. All three lanes have different counts → full abstain
    // ---------------------------------------------------------------

    @Test
    void allDifferentCounts_abstains() {
        TraceWindow pre = window("PRE_UPGRADE", 0);
        TraceWindow post1 = window("POST_STAGE_1", 0);
        TraceWindow postF = window("POST_FINAL_STAGE", 0, 1);

        WindowedTrace oo = traceOf(pre, post1, postF); // 3
        WindowedTrace ro = traceOf(pre); // 1
        WindowedTrace nn = traceOf(pre, post1); // 2

        List<FuzzingServer.AlignedWindow> result = FuzzingServer
                .alignWindows(oo, ro, nn);

        assertTrue(result.isEmpty(),
                "Should abstain when all three lanes have different counts");
    }

    // ---------------------------------------------------------------
    // 6. Non-matching keys in the partial-alignment path → full abstain
    // ---------------------------------------------------------------

    @Test
    void partialAlignment_nonMatchingKeys_abstains() {
        TraceWindow pre = window("PRE_UPGRADE", 0);
        TraceWindow post1 = window("POST_STAGE_1", 0);
        // Rolling has a key that doesn't appear in baselines
        TraceWindow mystery = window("UNKNOWN_STAGE", 0);

        WindowedTrace oo = traceOf(pre, post1);
        WindowedTrace ro = traceOf(mystery); // 1 window, no matching key
        WindowedTrace nn = traceOf(pre, post1);

        List<FuzzingServer.AlignedWindow> result = FuzzingServer
                .alignWindows(oo, ro, nn);

        assertTrue(result.isEmpty(),
                "Should abstain when rolling stage key doesn't match any baseline window");
    }

    // ---------------------------------------------------------------
    // 7. Partial alignment with tail-missing window (RO missing last)
    // ---------------------------------------------------------------

    @Test
    void partialAlignment_tailMissing_matchesTwoWindows() {
        TraceWindow pre = window("PRE_UPGRADE", 0);
        TraceWindow post1 = window("POST_STAGE_1", 0);
        TraceWindow postF = window("POST_FINAL_STAGE", 0, 1);

        WindowedTrace oo = traceOf(pre, post1, postF);
        WindowedTrace ro = traceOf(pre, post1); // missing postF
        WindowedTrace nn = traceOf(pre, post1, postF);

        List<FuzzingServer.AlignedWindow> result = FuzzingServer
                .alignWindows(oo, ro, nn);

        assertEquals(2, result.size());
        assertEquals("PRE_UPGRADE",
                result.get(0).rolling.comparisonStageId);
        assertEquals("POST_STAGE_1",
                result.get(1).rolling.comparisonStageId);
    }

    // ---------------------------------------------------------------
    // 8. Partial alignment: different transition node sets → mismatch
    // ---------------------------------------------------------------

    @Test
    void partialAlignment_differentTransitionNodes_abstains() {
        TraceWindow post1_node0 = window("POST_STAGE_1", 0);
        TraceWindow post1_node1 = window("POST_STAGE_1", 1);
        TraceWindow postF = window("POST_FINAL_STAGE", 0, 1);

        // OO/NN have POST_STAGE_1 with node {0}, but RO has node {1}
        WindowedTrace oo = traceOf(post1_node0, postF);
        WindowedTrace ro = traceOf(post1_node1); // different node set
        WindowedTrace nn = traceOf(post1_node0, postF);

        List<FuzzingServer.AlignedWindow> result = FuzzingServer
                .alignWindows(oo, ro, nn);

        assertTrue(result.isEmpty(),
                "Should abstain when transition node sets differ");
    }

    // ---------------------------------------------------------------
    // 9. Duplicate stage keys in partial-alignment path → full abstain
    // (Executor can emit repeated POST_FINAL_STAGE after the
    // normalized node set is already complete)
    // ---------------------------------------------------------------

    @Test
    void partialAlignment_duplicateStageKeys_abstains() {
        TraceWindow post1 = window("POST_STAGE_1", 0);
        TraceWindow postF = window("POST_FINAL_STAGE", 0, 1);
        // A second POST_FINAL_STAGE with the same key — ambiguous
        TraceWindow postF2 = window("POST_FINAL_STAGE", 0, 1);

        // OO has duplicate POST_FINAL_STAGE keys → ambiguous binding
        WindowedTrace oo = traceOf(post1, postF, postF2);
        WindowedTrace ro = traceOf(post1, postF); // 2 windows
        WindowedTrace nn = traceOf(post1, postF, postF2);

        List<FuzzingServer.AlignedWindow> result = FuzzingServer
                .alignWindows(oo, ro, nn);

        assertTrue(result.isEmpty(),
                "Should abstain when duplicate stage keys make alignment ambiguous");
    }
}
