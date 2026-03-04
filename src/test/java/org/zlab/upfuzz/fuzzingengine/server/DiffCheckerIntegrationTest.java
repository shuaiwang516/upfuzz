package org.zlab.upfuzz.fuzzingengine.server;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.zlab.upfuzz.fuzzingengine.Config;

/**
 * Integration tests for DiffReportHelper after Checker C/E removal.
 */
class DiffCheckerIntegrationTest {

    @BeforeAll
    static void initConfig() {
        if (Config.getConf() == null) {
            new Config();
        }
    }

    // --- DiffReportHelper filename tests ---

    @Test
    void reportFileNameIncludesLaneTag() {
        String name = DiffReportHelper.reportFileName(
                DiffReportHelper.CheckerType.EVENT_CRASH, 42, "Rolling");
        assertEquals("event_crash_42_Rolling.report", name);
    }

    @Test
    void reportFileNameWithoutLane() {
        String name = DiffReportHelper.reportFileName(
                DiffReportHelper.CheckerType.CROSS_CLUSTER_INCONSISTENCY,
                99, null);
        assertEquals("inconsistency_crosscluster_99.report", name);
    }

    @Test
    void reportFileNameSanitizesSpaces() {
        String name = DiffReportHelper.reportFileName(
                DiffReportHelper.CheckerType.ERROR_LOG, 7, "Only Old");
        assertEquals("error_log_7_OnlyOld.report", name);
    }

    // --- DiffReportHelper header tests ---

    @Test
    void crossClusterHeaderFormat() {
        String header = DiffReportHelper.crossClusterHeader();
        assertTrue(
                header.contains("Cross-cluster inconsistency detected"));
        assertFalse(header.contains("full-stop"));
    }

    @Test
    void eventCrashHeaderFormat() {
        String header = DiffReportHelper.eventCrashHeader("Rolling");
        assertEquals("[Rolling] [Event execution failure]\n", header);
    }

    @Test
    void errorLogHeaderFormat() {
        String header = DiffReportHelper.errorLogHeader("OnlyNew");
        assertEquals("[OnlyNew] [ERROR LOG]\n", header);
    }

    // --- Metadata block test ---

    @Test
    void metadataBlockContainsAllFields() {
        String block = DiffReportHelper.buildMetadataBlock(
                DiffReportHelper.CheckerType.CROSS_CLUSTER_INCONSISTENCY,
                null,
                DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE,
                42, "test0", "exec-001");
        assertTrue(
                block.contains("checkerType = inconsistency_crosscluster"));
        assertTrue(block.contains(
                "verdict = ROLLING_UPGRADE_BUG_CANDIDATE"));
        assertTrue(block.contains("testID = 42"));
        assertTrue(block.contains("configIdx = test0"));
        assertTrue(block.contains("executionId = exec-001"));
        assertFalse(block.contains("lane = "));
    }

    @Test
    void metadataBlockIncludesLaneWhenProvided() {
        String block = DiffReportHelper.buildMetadataBlock(
                DiffReportHelper.CheckerType.ERROR_LOG,
                "Rolling",
                DiffVerdict.ORACLE_NOISE,
                7, null, null);
        assertTrue(block.contains("lane = Rolling"));
        assertFalse(block.contains("configIdx"));
        assertFalse(block.contains("executionId"));
    }
}
