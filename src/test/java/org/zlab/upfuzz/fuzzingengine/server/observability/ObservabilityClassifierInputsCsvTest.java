package org.zlab.upfuzz.fuzzingengine.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase-5 review-round-2 regression coverage for the
 * {@code trace_window_classifier_inputs.csv} sidecar emission contract:
 *
 * <ul>
 *   <li>File absent  => dump was off during the live run.</li>
 *   <li>Header-only  => dump was on but recorded zero tuples.</li>
 *   <li>Rows present => dump was on and recorded classifier inputs.</li>
 * </ul>
 */
public class ObservabilityClassifierInputsCsvTest {

    /**
     * Test double that bypasses the JVM-global {@code Config} lookup so
     * the test can exercise both dump states without standing up
     * {@link org.zlab.upfuzz.fuzzingengine.Config#setInstance}.
     */
    private static final class FakeMetrics extends ObservabilityMetrics {
        private final boolean dumpEnabled;

        FakeMetrics(Path outputDir, boolean dumpEnabled) {
            super(outputDir);
            this.dumpEnabled = dumpEnabled;
        }

        @Override
        protected boolean isTraceFlowTupleDumpEnabled() {
            return dumpEnabled;
        }
    }

    @Test
    public void sidecarNotEmittedWhenDumpDisabled(@TempDir Path tmp)
            throws IOException {
        FakeMetrics m = new FakeMetrics(tmp, /* dumpEnabled */ false);
        // Record a row anyway — the contract is the WRITER short-circuits,
        // not the RECORDER. This also guards against a future regression
        // where someone routes the recorder through the flag but forgets
        // the writer.
        m.recordClassifierInput(sampleRow());
        m.writeAllArtifacts();
        Path sidecar = tmp.resolve("trace_window_classifier_inputs.csv");
        assertFalse(Files.exists(sidecar),
                "trace_window_classifier_inputs.csv must NOT exist when dump is disabled");
    }

    @Test
    public void sidecarDeletesStaleFileWhenDumpDisabled(@TempDir Path tmp)
            throws IOException {
        // Simulate a prior enabled run that wrote the sidecar into the
        // same directory. The next write pass with the dump disabled
        // must remove the stale file so "absent = unavailable" holds.
        Path stale = tmp.resolve("trace_window_classifier_inputs.csv");
        Files.write(stale, "stale\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.exists(stale));
        FakeMetrics m = new FakeMetrics(tmp, /* dumpEnabled */ false);
        m.writeAllArtifacts();
        assertFalse(Files.exists(stale),
                "stale sidecar from a prior enabled run must be removed when dump is disabled");
    }

    @Test
    public void sidecarHeaderOnlyWhenDumpEnabledButNoRows(@TempDir Path tmp)
            throws IOException {
        FakeMetrics m = new FakeMetrics(tmp, /* dumpEnabled */ true);
        // Do NOT record any rows.
        m.writeAllArtifacts();
        Path sidecar = tmp.resolve("trace_window_classifier_inputs.csv");
        assertTrue(Files.exists(sidecar),
                "sidecar must exist when the dump is enabled (even with zero rows)");
        List<String> lines = Files.readAllLines(sidecar);
        assertEquals(1, lines.size(),
                "header-only expected when zero classifier inputs were recorded");
        assertEquals(ClassifierInputRow.csvHeader(), lines.get(0));
    }

    @Test
    public void sidecarCarriesRowsWhenDumpEnabledAndPopulated(@TempDir Path tmp)
            throws IOException {
        FakeMetrics m = new FakeMetrics(tmp, /* dumpEnabled */ true);
        m.recordClassifierInput(sampleRow());
        m.recordClassifierInput(sampleRow());
        m.writeAllArtifacts();
        Path sidecar = tmp.resolve("trace_window_classifier_inputs.csv");
        List<String> lines = Files.readAllLines(sidecar);
        assertEquals(3, lines.size(),
                "header + two populated rows");
    }

    private static ClassifierInputRow sampleRow() {
        return new ClassifierInputRow(
                /* round */ 0, /* testPacketId */ 0, /* windowOrdinal */ 0,
                /* comparisonStage */ "POST_STAGE_1", /* lane */ "ro",
                /* protocol */ "cassandra", /* rpcService */ null,
                /* rpcMethod */ null, /* messageType */ "PAXOS2_PROPOSE_REQ",
                /* messageKind */ null, /* payloadType */ null,
                /* currentFamily */ "CASSANDRA_PAXOS", /* count */ 5);
    }
}
