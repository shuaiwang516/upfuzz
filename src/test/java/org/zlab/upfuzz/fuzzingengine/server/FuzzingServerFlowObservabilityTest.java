package org.zlab.upfuzz.fuzzingengine.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.zlab.net.tracker.classifier.ProtocolFamily;
import org.zlab.net.tracker.flow.CorrelationSource;
import org.zlab.net.tracker.flow.FlowBoundaryStatus;
import org.zlab.net.tracker.flow.FlowExtractionResult;
import org.zlab.net.tracker.flow.TraceFlowKey;
import org.zlab.net.tracker.flow.TraceFlowSummary;
import org.zlab.upfuzz.fuzzingengine.server.observability.TraceEvidenceStrength;
import org.zlab.upfuzz.fuzzingengine.server.observability.WindowTriggerRow;
import org.zlab.upfuzz.fuzzingengine.trace.AlignedWindowFlowSummaries;

/**
 * Unit coverage for the Phase 2 CSV glue that turns a rolling-lane
 * {@link FlowExtractionResult} plus ranked divergent families into the
 * compact {@link WindowTriggerRow} columns. Guards the CSV format against
 * accidental reordering during future edits.
 */
public class FuzzingServerFlowObservabilityTest {

    private static FlowExtractionResult buildRollingResult() {
        Map<String, Integer> putLabels = new LinkedHashMap<>();
        putLabels.put("ClientService#Mutate[PUT]", 2);
        Map<String, Integer> delLabels = new LinkedHashMap<>();
        delLabels.put("ClientService#Mutate[DELETE]", 1);
        TraceFlowKey keyPut = new TraceFlowKey("POST_STAGE_1", "client", "rs",
                ProtocolFamily.HBASE_CLIENT_MUTATION_OR_MULTI, "call-1",
                CorrelationSource.EXPLICIT_LOGICAL_ID);
        TraceFlowKey keyDel = new TraceFlowKey("POST_STAGE_1", "client", "rs",
                ProtocolFamily.HBASE_CLIENT_MUTATION_OR_MULTI, "call-2",
                CorrelationSource.EXPLICIT_LOGICAL_ID);
        List<TraceFlowSummary> flows = new ArrayList<>();
        flows.add(new TraceFlowSummary(keyPut, 2, true, true,
                FlowBoundaryStatus.CROSSING, putLabels));
        flows.add(new TraceFlowSummary(keyDel, 1, true, false,
                FlowBoundaryStatus.NONE, delLabels));
        return new FlowExtractionResultBuilder().withFlows(flows)
                .withExplicit(2).build();
    }

    @Test
    public void formatTopDivergentFamilies_producesStableOrdering() {
        List<AlignedWindowFlowSummaries.DivergentFamily> families = new ArrayList<>();
        families.add(new AlignedWindowFlowSummaries.DivergentFamily(
                ProtocolFamily.HBASE_CLIENT_MUTATION_OR_MULTI, 10, 1, 2,
                8.5));
        families.add(new AlignedWindowFlowSummaries.DivergentFamily(
                ProtocolFamily.CASSANDRA_SCHEMA_SYNC, 3, 0, 0, 3.0));
        String csv = FuzzingServer.formatTopDivergentFamilies(families);
        assertEquals("HBASE_CLIENT_MUTATION_OR_MULTI=8.50@ro=10/oo=1/nn=2;"
                + "CASSANDRA_SCHEMA_SYNC=3.00@ro=3/oo=0/nn=0", csv);
    }

    @Test
    public void formatTopDivergentFamilies_isEmpty_whenNoDivergence() {
        assertEquals("", FuzzingServer.formatTopDivergentFamilies(
                Collections.<AlignedWindowFlowSummaries.DivergentFamily> emptyList()));
        assertEquals("", FuzzingServer.formatTopDivergentFamilies(null));
    }

    @Test
    public void formatTopDivergentDetails_emitsPerFamilyDetailLabels() {
        List<AlignedWindowFlowSummaries.DivergentFamily> families = new ArrayList<>();
        families.add(new AlignedWindowFlowSummaries.DivergentFamily(
                ProtocolFamily.HBASE_CLIENT_MUTATION_OR_MULTI, 3, 0, 0, 3.0));
        String csv = FuzzingServer.formatTopDivergentDetails(families,
                buildRollingResult(), /* cap */ 5);
        assertTrue(csv.startsWith("HBASE_CLIENT_MUTATION_OR_MULTI:"),
                "family label leads the row: " + csv);
        assertTrue(csv.contains("ClientService#Mutate[PUT]=2"), csv);
        assertTrue(csv.contains("ClientService#Mutate[DELETE]=1"), csv);
    }

    @Test
    public void formatTopDivergentDetails_sortsByCountDescending_beforeCapping() {
        // Build a rolling result whose per-family histogram has the
        // high-count label ("mkdirs") sitting AFTER a lower-count label
        // ("sendHeartbeat") in insertion order. With a cap of 1, the
        // emitted row must keep the high-count label — the regression
        // bug dropped mkdirs because it walked the histogram in
        // insertion order.
        Map<String, Integer> labels = new LinkedHashMap<>();
        labels.put("ClientNamenodeProtocol#sendHeartbeat", 1);
        labels.put("ClientNamenodeProtocol#mkdirs", 9);
        labels.put("ClientNamenodeProtocol#rename2", 5);
        TraceFlowKey key = new TraceFlowKey("POST_STAGE_1", "client",
                "namenode", ProtocolFamily.HDFS_CLIENT_NAMESPACE_MUTATION,
                "f1", CorrelationSource.EXPLICIT_LOGICAL_ID);
        List<TraceFlowSummary> flows = new ArrayList<>();
        flows.add(new TraceFlowSummary(key, 15, true, true,
                FlowBoundaryStatus.NONE, labels));
        FlowExtractionResult rolling = new FlowExtractionResultBuilder()
                .withFlows(flows).withExplicit(1).build();

        List<AlignedWindowFlowSummaries.DivergentFamily> families = new ArrayList<>();
        families.add(new AlignedWindowFlowSummaries.DivergentFamily(
                ProtocolFamily.HDFS_CLIENT_NAMESPACE_MUTATION, 15, 1, 1,
                14.0));

        String capOne = FuzzingServer.formatTopDivergentDetails(families,
                rolling, /* cap */ 1);
        assertEquals(
                "HDFS_CLIENT_NAMESPACE_MUTATION:ClientNamenodeProtocol#mkdirs=9",
                capOne,
                "highest-count label survives the cap; insertion order must not matter");

        // Cap=2 keeps the top two in count order even though rename2 (5)
        // comes after mkdirs (9) in insertion order.
        String capTwo = FuzzingServer.formatTopDivergentDetails(families,
                rolling, /* cap */ 2);
        assertEquals(
                "HDFS_CLIENT_NAMESPACE_MUTATION:ClientNamenodeProtocol#mkdirs=9|ClientNamenodeProtocol#rename2=5",
                capTwo);

        // Ties break by label name so the output remains deterministic
        // between runs.
        Map<String, Integer> ties = new LinkedHashMap<>();
        ties.put("ClientNamenodeProtocol#rename2", 3);
        ties.put("ClientNamenodeProtocol#mkdirs", 3);
        List<TraceFlowSummary> tieFlows = new ArrayList<>();
        tieFlows.add(new TraceFlowSummary(key, 6, true, true,
                FlowBoundaryStatus.NONE, ties));
        FlowExtractionResult tieRolling = new FlowExtractionResultBuilder()
                .withFlows(tieFlows).withExplicit(1).build();
        String tied = FuzzingServer.formatTopDivergentDetails(families,
                tieRolling, /* cap */ 2);
        assertTrue(
                tied.contains("ClientNamenodeProtocol#mkdirs=3")
                        && tied.contains("ClientNamenodeProtocol#rename2=3"),
                tied);
        int mkdirsPos = tied.indexOf("mkdirs=3");
        int renamePos = tied.indexOf("rename2=3");
        assertTrue(mkdirsPos >= 0 && renamePos >= 0 && mkdirsPos < renamePos,
                "tie breaks alphabetically: mkdirs before rename2, got "
                        + tied);
    }

    @Test
    public void windowTriggerRow_csvIncludesNewFlowColumns() {
        WindowTriggerRow row = new WindowTriggerRow(42L, 7, 0, "POST_STAGE_1",
                10, 3, 1, 0, 0.10, 0.00, 0.5, 0.5, 0.9, 0.5, 0.4, true, true,
                false, false, 3, 0, 1, TraceEvidenceStrength.WEAK, true, 20,
                10, 6, 2, 2, 3, 4, 5,
                /* flowExplicitIdRolling */ 2,
                /* flowFallbackRolling */ 1,
                /* flowGroupingFailedRolling */ 0,
                /* flowBoundaryInvolvedRolling */ 1,
                /* flowRoleAmbiguousBoundaryRolling */ 0,
                /* flowUnresolvedBoundaryRolling */ 0,
                "HBASE_CLIENT_MUTATION_OR_MULTI=5.00@ro=6/oo=1/nn=1",
                "HBASE_CLIENT_MUTATION_OR_MULTI:ClientService#Mutate[PUT]=2");
        String csv = row.toCsvRow();
        String[] columns = csv.split(",", -1);
        assertEquals(WindowTriggerRow.csvHeader().split(",", -1).length,
                columns.length);

        String header = WindowTriggerRow.csvHeader();
        int total = header.split(",", -1).length;
        // Spot-check a few newer columns land where the header declares.
        List<String> headerList = List.of(header.split(",", -1));
        assertEquals("4", columns[headerList.indexOf("flow_total_rolling")]);
        assertEquals("2",
                columns[headerList.indexOf("flow_explicit_id_rolling")]);
        assertEquals("1", columns[headerList
                .indexOf("flow_boundary_involved_rolling")]);
        assertTrue(csv.contains("HBASE_CLIENT_MUTATION_OR_MULTI=5.00"));
        assertTrue(total > 30);
    }

    // --- Test-only helpers --------------------------------------------------

    private static final class FlowExtractionResultBuilder {
        private List<TraceFlowSummary> flows = Collections.emptyList();
        private int explicit;
        private int fallback;
        private int failed;

        FlowExtractionResultBuilder withFlows(List<TraceFlowSummary> flows) {
            this.flows = flows;
            return this;
        }

        FlowExtractionResultBuilder withExplicit(int n) {
            this.explicit = n;
            return this;
        }

        FlowExtractionResult build() {
            try {
                java.lang.reflect.Constructor<FlowExtractionResult> ctor = FlowExtractionResult.class
                        .getDeclaredConstructor(List.class, int.class,
                                int.class, int.class);
                ctor.setAccessible(true);
                return ctor.newInstance(flows, explicit, fallback, failed);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(
                        "FlowExtractionResult ctor signature changed", e);
            }
        }
    }
}
