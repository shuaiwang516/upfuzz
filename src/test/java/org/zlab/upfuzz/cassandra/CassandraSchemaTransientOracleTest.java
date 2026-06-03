package org.zlab.upfuzz.cassandra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.zlab.upfuzz.fuzzingengine.packet.TestPlanFeedbackPacket;
import org.zlab.upfuzz.fuzzingengine.packet.ValidationResult;
import org.zlab.upfuzz.fuzzingengine.server.CrossClusterComparisonOutcome;
import org.zlab.upfuzz.fuzzingengine.server.observability.StructuredCandidateStrength;

class CassandraSchemaTransientOracleTest {
    @Test
    void demotesInvalidRequestAndZeroRowHeaderDiff() {
        TestPlanFeedbackPacket[] packets = packetsWithSchemaChange();
        packets[0].validationResults = Arrays.asList(
                ok("SELECT renamed FROM ks.t;", ""),
                ok("SELECT * FROM ks.t;", " a | b\n---+---\n\n(0 rows)\n"));
        packets[1].validationResults = Arrays.asList(
                fail("SELECT renamed FROM ks.t;", "InvalidRequest"),
                ok("SELECT * FROM ks.t;",
                        " old | a | b\n-----+---+---\n\n(0 rows)\n"));
        packets[2].validationResults = Arrays.asList(
                ok("SELECT renamed FROM ks.t;", ""),
                ok("SELECT * FROM ks.t;", " a | b\n---+---\n\n(0 rows)\n"));

        CrossClusterComparisonOutcome demoted =
                CassandraSchemaTransientOracle.demoteIfUnconfirmed(
                        strongOutcome(), packets);

        assertEquals(StructuredCandidateStrength.WEAK, demoted.strength);
        assertTrue(demoted.report.contains(
                "[cassandra-schema-transient] demotedTo=WEAK"));
        assertTrue(demoted.report.contains("affectedRows=2"));
    }

    @Test
    void keepsInvalidRequestWithoutSchemaChangeStrong() {
        TestPlanFeedbackPacket[] packets = packetsWithoutSchemaChange();
        packets[0].validationResults = Arrays.asList(
                ok("SELECT v FROM ks.t;", ""));
        packets[1].validationResults = Arrays.asList(
                fail("SELECT v FROM ks.t;", "InvalidRequest"));
        packets[2].validationResults = Arrays.asList(
                ok("SELECT v FROM ks.t;", ""));

        CrossClusterComparisonOutcome outcome = strongOutcome();

        assertSame(outcome, CassandraSchemaTransientOracle
                .demoteIfUnconfirmed(outcome, packets));
    }

    @Test
    void keepsNonEmptyPayloadDiffStrong() {
        TestPlanFeedbackPacket[] packets = packetsWithSchemaChange();
        packets[0].validationResults = Arrays.asList(
                ok("SELECT * FROM ks.t;", " a\n---\n 1\n\n(1 rows)\n"));
        packets[1].validationResults = Arrays.asList(
                ok("SELECT * FROM ks.t;", " a\n---\n 2\n\n(1 rows)\n"));
        packets[2].validationResults = Arrays.asList(
                ok("SELECT * FROM ks.t;", " a\n---\n 1\n\n(1 rows)\n"));

        CrossClusterComparisonOutcome outcome = strongOutcome();

        assertSame(outcome, CassandraSchemaTransientOracle
                .demoteIfUnconfirmed(outcome, packets));
    }

    private static TestPlanFeedbackPacket[] packetsWithSchemaChange() {
        TestPlanFeedbackPacket[] packets = packetsWithoutSchemaChange();
        packets[1].fullSequence =
                "test plan:\n[Command] Execute {ALTER TABLE ks.t DROP b;}";
        return packets;
    }

    private static TestPlanFeedbackPacket[] packetsWithoutSchemaChange() {
        return new TestPlanFeedbackPacket[] {
                new TestPlanFeedbackPacket("cassandra", "test", 1, null),
                new TestPlanFeedbackPacket("cassandra", "test", 1, null),
                new TestPlanFeedbackPacket("cassandra", "test", 1, null)
        };
    }

    private static CrossClusterComparisonOutcome strongOutcome() {
        return new CrossClusterComparisonOutcome(
                true,
                StructuredCandidateStrength.STRONG,
                "[confidence] strength=STRONG, containsUnknown=false, "
                        + "containsDaemonError=false\n",
                false,
                false);
    }

    private static ValidationResult ok(String command, String stdout) {
        return new ValidationResult(command, 0, stdout, "", "OK");
    }

    private static ValidationResult fail(String command,
            String failureClass) {
        return new ValidationResult(command, 1, "", failureClass,
                failureClass);
    }
}
