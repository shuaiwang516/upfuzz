package org.zlab.upfuzz.fuzzingengine.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zlab.upfuzz.fuzzingengine.TraceSystemPreset;
import org.zlab.upfuzz.fuzzingengine.replay.TraceReplay.Tier;

/** Phase 5 TraceReplay coverage. */
public class TraceReplayTest {

    @Test
    public void parseCsvHandlesQuotedFields() {
        String[] cols = TraceReplay.parseCsv(
                "1,\"a,b\",c,\"q\"\"u\",4");
        assertEquals(5, cols.length);
        assertEquals("1", cols[0]);
        assertEquals("a,b", cols[1]);
        assertEquals("c", cols[2]);
        assertEquals("q\"u", cols[3]);
        assertEquals("4", cols[4]);
    }

    @Test
    public void schemaDetectsHistoricalTier() {
        String header = String.join(",",
                "round", "test_packet_id", "window_ordinal", "comparison_stage",
                "total_messages", "total_all_three_count", "rolling_exclusive",
                "rolling_missing", "rolling_exclusive_fraction",
                "rolling_missing_fraction", "sim_oo_ro", "sim_ro_nn",
                "sim_baseline", "rolling_min_similarity",
                "rolling_divergence_margin", "window_has_enough_events",
                "window_sim_fired", "tri_diff_exclusive_fired",
                "tri_diff_missing_fired", "baseline_shared_count",
                "changed_message_count", "upgraded_boundary_event_count",
                "trace_evidence_strength", "support_gate_passed");
        TraceReplay.CsvSchema s = TraceReplay.CsvSchema.detect(header);
        assertSame(Tier.HISTORICAL, s.tier);
    }

    @Test
    public void schemaDetectsPostInstrumentationTier() {
        String header = TraceReplay.CsvSchema.detect(buildPhase3Header()).tier
                .name();
        assertEquals(Tier.POST_INSTRUMENTATION.name(), header);
    }

    @Test
    public void historicalReplayPromotesGateAndCorroboration(
            @TempDir Path tmp) throws IOException {
        Path obs = tmp.resolve("observability");
        Files.createDirectories(obs);
        Path csv = obs.resolve("trace_window_summary.csv");
        StringBuilder sb = new StringBuilder();
        sb.append("round,test_packet_id,window_ordinal,comparison_stage,")
                .append("total_messages,total_all_three_count,rolling_exclusive,")
                .append("rolling_missing,rolling_exclusive_fraction,")
                .append("rolling_missing_fraction,sim_oo_ro,sim_ro_nn,")
                .append("sim_baseline,rolling_min_similarity,")
                .append("rolling_divergence_margin,window_has_enough_events,")
                .append("window_sim_fired,tri_diff_exclusive_fired,")
                .append("tri_diff_missing_fired,baseline_shared_count,")
                .append("changed_message_count,upgraded_boundary_event_count,")
                .append("trace_evidence_strength,support_gate_passed\n");
        // Row 1: WEAK + corroboration + mixed-version stage → STRONG under
        // historical replay.
        sb.append("0,0,0,POST_STAGE_1,200,12,5,1,0.0250,0.0050,0.5,0.4,0.45,")
                .append("0.4,-0.05,true,true,true,false,17,0,3,WEAK,true\n");
        // Row 2: support gate failed → UNSUPPORTED.
        sb.append("1,0,0,POST_STAGE_1,200,12,5,1,0.0250,0.0050,0.5,0.4,0.45,")
                .append("0.4,-0.05,true,true,true,false,17,0,0,WEAK,false\n");
        // Row 3: window did not fire → NONE.
        sb.append("2,0,0,POST_STAGE_1,200,12,0,0,0.0,0.0,1.0,1.0,1.0,1.0,0.0,")
                .append("true,false,false,false,17,0,0,NONE,true\n");
        Files.write(csv, sb.toString().getBytes(StandardCharsets.UTF_8));

        TraceReplay.Args args = new TraceReplay.Args();
        args.inputs.add(obs);
        args.preset = TraceSystemPreset.HDFS;
        args.system = "hdfs";
        args.outDir = tmp.resolve("replay-out");
        TraceReplay.run(args);

        Path summary = args.outDir.resolve("replay_summary.csv");
        Path perHost = args.outDir.resolve("replay_per_host.csv");
        assertTrue(Files.isRegularFile(summary));
        assertTrue(Files.isRegularFile(perHost));
        List<String> sumLines = Files.readAllLines(summary);
        // Header + one totals row.
        assertEquals(2, sumLines.size());
        // STRONG count is the ninth-from-end column in the totals row;
        // simpler: parse explicitly.
        String totalsRow = sumLines.get(1);
        String[] cols = TraceReplay.parseCsv(totalsRow);
        // Index of strong_replay (0-based): preset(0), system(1), tier(2),
        // strong_threshold(3), weak_threshold(4), min_baseline(5),
        // min_rolling(6), background_cap(7), boundary_bonus(8),
        // order_bonus_cap(9), hosts(10), total_rows(11),
        // strong_replay(12), weak_replay(13), unsupported_replay(14),
        // unsupported_repeatable_replay(15), none_replay(16)...
        int strongReplay = Integer.parseInt(cols[12]);
        int weakReplay = Integer.parseInt(cols[13]);
        int unsupportedReplay = Integer.parseInt(cols[14]);
        int noneReplay = Integer.parseInt(cols[16]);
        assertEquals(1, strongReplay,
                "Row 1 must promote to STRONG under historical replay");
        assertEquals(0, weakReplay);
        assertEquals(1, unsupportedReplay,
                "Row 2 must drop to UNSUPPORTED (support gate failed)");
        assertEquals(1, noneReplay,
                "Row 3 must remain NONE (window did not fire)");
    }

    @Test
    public void postInstrumentationReplayUsesCompositeScorer(
            @TempDir Path tmp) throws IOException {
        Path obs = tmp.resolve("observability");
        Files.createDirectories(obs);
        Path csv = obs.resolve("trace_window_summary.csv");
        StringBuilder sb = new StringBuilder();
        sb.append(buildPhase3Header()).append('\n');
        // Row 1: composite=0.42, support=FLOW_BACKED, mixed stage,
        // baseline=0.6, rolling=0.4, rolling_only_upgrade_critical=false
        // → STRONG under HDFS preset (strong threshold 0.32).
        sb.append(buildPhase3Row("POST_STAGE_1", true, "FLOW_BACKED",
                0.42, 0.60, 0.40, "STRONG", true, false));
        sb.append('\n');
        // Row 2: composite=0.10, support=FAMILY_BACKED, mixed stage
        // → WEAK (composite below strong threshold).
        sb.append(buildPhase3Row("POST_STAGE_1", true, "FAMILY_BACKED",
                0.10, 0.60, 0.40, "WEAK", true, false));
        sb.append('\n');
        // Row 3: support=BACKGROUND_ONLY → WEAK (cannot reach STRONG).
        sb.append(buildPhase3Row("POST_STAGE_1", true, "BACKGROUND_ONLY",
                0.50, 0.60, 0.40, "WEAK", true, false));
        sb.append('\n');
        // Row 4: support=UNSUPPORTED, no rolling-only upgrade-critical
        // → UNSUPPORTED.
        sb.append(buildPhase3Row("POST_STAGE_1", true, "UNSUPPORTED",
                0.50, 0.60, 0.40, "UNSUPPORTED", true, false));
        sb.append('\n');
        // Row 5: support=UNSUPPORTED, rolling-only upgrade-critical
        // present → UNSUPPORTED_BUT_REPEATABLE.
        sb.append(buildPhase3Row("POST_STAGE_1", true, "UNSUPPORTED",
                0.50, 0.60, 0.40, "WEAK", true, true));
        sb.append('\n');
        Files.write(csv, sb.toString().getBytes(StandardCharsets.UTF_8));

        TraceReplay.Args args = new TraceReplay.Args();
        args.inputs.add(obs);
        args.preset = TraceSystemPreset.HDFS;
        args.system = "hdfs";
        args.outDir = tmp.resolve("replay-out");
        args.tier = Tier.POST_INSTRUMENTATION;
        TraceReplay.run(args);

        Path summary = args.outDir.resolve("replay_summary.csv");
        String totalsRow = Files.readAllLines(summary).get(1);
        String[] cols = TraceReplay.parseCsv(totalsRow);
        int strong = Integer.parseInt(cols[12]);
        int weak = Integer.parseInt(cols[13]);
        int unsupported = Integer.parseInt(cols[14]);
        int unsupportedRepeatable = Integer.parseInt(cols[15]);
        assertEquals(1, strong, "Row 1 → STRONG");
        assertEquals(2, weak, "Row 2 + Row 3 → WEAK");
        assertEquals(1, unsupported, "Row 4 → UNSUPPORTED");
        assertEquals(1, unsupportedRepeatable,
                "Row 5 → UNSUPPORTED_BUT_REPEATABLE");
    }

    @Test
    public void missingOnlyFiredWindowIsScoredNotDropped(@TempDir Path tmp)
            throws IOException {
        Path obs = tmp.resolve("observability");
        Files.createDirectories(obs);
        Path csv = obs.resolve("trace_window_summary.csv");
        StringBuilder sb = new StringBuilder();
        sb.append("round,test_packet_id,window_ordinal,comparison_stage,")
                .append("total_messages,total_all_three_count,rolling_exclusive,")
                .append("rolling_missing,rolling_exclusive_fraction,")
                .append("rolling_missing_fraction,sim_oo_ro,sim_ro_nn,")
                .append("sim_baseline,rolling_min_similarity,")
                .append("rolling_divergence_margin,window_has_enough_events,")
                .append("window_sim_fired,tri_diff_exclusive_fired,")
                .append("tri_diff_missing_fired,baseline_shared_count,")
                .append("changed_message_count,upgraded_boundary_event_count,")
                .append("trace_evidence_strength,support_gate_passed\n");
        // Missing-only fired window: window_sim_fired=false,
        // exclusive_fired=false, missing_fired=true. Support gate
        // passed and corroboration present, on a mixed-version stage,
        // so the historical replay rule must promote to STRONG.
        // Pre-fix this row was silently dropped to NONE because
        // windowFired ignored tri_diff_missing_fired.
        sb.append("0,0,0,POST_STAGE_1,200,12,0,5,0.0000,0.0250,0.5,0.4,0.45,")
                .append("0.4,-0.05,true,false,false,true,17,0,3,WEAK,true\n");
        Files.write(csv, sb.toString().getBytes(StandardCharsets.UTF_8));

        TraceReplay.Args args = new TraceReplay.Args();
        args.inputs.add(obs);
        args.preset = TraceSystemPreset.HDFS;
        args.system = "hdfs";
        args.outDir = tmp.resolve("replay-out");
        TraceReplay.run(args);

        String[] cols = TraceReplay
                .parseCsv(Files.readAllLines(args.outDir.resolve(
                        "replay_summary.csv")).get(1));
        int strong = Integer.parseInt(cols[12]);
        int none = Integer.parseInt(cols[16]);
        assertEquals(1, strong,
                "missing-only fired window must be scored, not dropped");
        assertEquals(0, none,
                "missing-only fired window must NOT count as NONE post-fix");
    }

    @Test
    public void perWindowAndServiceMethodOutputsAreEmitted(@TempDir Path tmp)
            throws IOException {
        Path obs = tmp.resolve("observability");
        Files.createDirectories(obs);
        Path csv = obs.resolve("trace_window_summary.csv");
        StringBuilder sb = new StringBuilder();
        sb.append(buildPhase3Header()).append('\n');
        // One STRONG row carrying a divergent-details column with two
        // family chunks and three labels, one of which the parser must
        // pick up correctly even with a vertical-bar inside one chunk.
        sb.append(buildPhase3RowWithDivergentDetails(
                "POST_STAGE_1", "FLOW_BACKED", 0.42, 0.60, 0.40, "STRONG",
                "HDFS_CLIENT_NAMESPACE_MUTATION:ClientNamenodeProtocolService#create=12|ClientNamenodeProtocolService#mkdirs=4;BACKGROUND:DatanodeProtocolService#heartbeat=99"));
        sb.append('\n');
        Files.write(csv, sb.toString().getBytes(StandardCharsets.UTF_8));

        TraceReplay.Args args = new TraceReplay.Args();
        args.inputs.add(obs);
        args.preset = TraceSystemPreset.HDFS;
        args.system = "hdfs";
        args.outDir = tmp.resolve("replay-out");
        TraceReplay.run(args);

        Path perWindow = args.outDir.resolve("replay_per_window.csv");
        Path serviceMethods = args.outDir
                .resolve("replay_top_service_methods.csv");
        assertTrue(Files.isRegularFile(perWindow),
                "replay_per_window.csv must exist");
        assertTrue(Files.isRegularFile(serviceMethods),
                "replay_top_service_methods.csv must exist");

        List<String> perWindowLines = Files.readAllLines(perWindow);
        // header + 1 data row
        assertEquals(2, perWindowLines.size());
        String[] perWindowCols = TraceReplay
                .parseCsv(perWindowLines.get(1));
        // host, round, packet, ordinal, stage, then three fired bools,
        // then original_strength, replay_strength, recommended_routing
        // — we check the routing column landed correctly.
        // Index of recommended_routing per the writer: position 10.
        assertEquals("MAIN_EXPLOIT", perWindowCols[10],
                "STRONG row must recommend MAIN_EXPLOIT routing");

        List<String> serviceLines = Files.readAllLines(serviceMethods);
        // header + 3 (two upgrade-critical labels + one background label)
        assertEquals(4, serviceLines.size());
        // Find the heartbeat row (highest count = 99, sorted first).
        String[] firstDataRow = TraceReplay.parseCsv(serviceLines.get(1));
        assertEquals("BACKGROUND", firstDataRow[0]);
        assertEquals("DatanodeProtocolService#heartbeat", firstDataRow[1]);
        assertEquals("99", firstDataRow[2]);
    }

    @Test
    public void tier2ReClassifiesViaProfileWhenSidecarPresent(
            @TempDir Path tmp) throws IOException {
        // Build an empty trace_window_summary.csv (header only) plus a
        // populated trace_window_classifier_inputs.csv sidecar. Register
        // a synthetic profile via --family-map-profile and assert the
        // replay re-classifies a tuple the live run flagged as UNKNOWN.
        Path obs = tmp.resolve("observability");
        Files.createDirectories(obs);
        Path summary = obs.resolve("trace_window_summary.csv");
        Files.write(summary,
                (buildPhase3Header() + "\n").getBytes(StandardCharsets.UTF_8));

        Path tuples = obs.resolve("trace_window_classifier_inputs.csv");
        StringBuilder sb = new StringBuilder();
        sb.append(
                "round,test_packet_id,window_ordinal,comparison_stage,lane,protocol,rpc_service,rpc_method,message_type,message_kind,payload_type,current_family,count\n");
        // Tuple A: live run classified as UNKNOWN; profile maps it to
        // CASSANDRA_REPAIR_OR_STREAM via messageType lookup.
        sb.append(
                "0,0,0,POST_STAGE_1,ro,cassandra,,,FUTURE_VERB_LONGTAIL,,,UNKNOWN,7\n");
        // Tuple B: live run classified as UNKNOWN; profile has no entry
        // for it. Stays UNKNOWN.
        sb.append(
                "0,0,0,POST_STAGE_1,ro,cassandra,,,COMPLETELY_UNCOVERED_VERB,,,UNKNOWN,3\n");
        Files.write(tuples, sb.toString().getBytes(StandardCharsets.UTF_8));

        Path profile = tmp.resolve("cassandra-test.yaml");
        Files.write(profile, ("id: cassandra-test\n"
                + "system: cassandra\n"
                + "familyMap:\n"
                + "  - messageType: FUTURE_VERB_LONGTAIL\n"
                + "    family: CASSANDRA_REPAIR_OR_STREAM\n").getBytes(
                        StandardCharsets.UTF_8));

        TraceReplay.Args args = new TraceReplay.Args();
        args.inputs.add(obs);
        args.preset = TraceSystemPreset.CASSANDRA;
        args.system = "cassandra";
        args.outDir = tmp.resolve("replay-out");
        args.familyMapProfile = profile;
        TraceReplay.run(args);

        Path changes = args.outDir.resolve("replay_classifier_changes.csv");
        assertTrue(Files.isRegularFile(changes),
                "replay_classifier_changes.csv must exist");
        List<String> lines = Files.readAllLines(changes);
        // The first line is a # comment with profile metadata.
        assertTrue(lines.get(0).startsWith("# profile_id=cassandra-test"));
        // Body must include exactly one re-tag row + family-transition row.
        boolean foundRetagged = false;
        boolean foundTransition = false;
        boolean foundTuple = false;
        for (String line : lines) {
            if (line.contains(",rows_retagged,1")) {
                foundRetagged = true;
            }
            if (line.contains(
                    "UNKNOWN->CASSANDRA_REPAIR_OR_STREAM,7")) {
                foundTransition = true;
            }
            if (line.contains("@FUTURE_VERB_LONGTAIL,7")) {
                foundTuple = true;
            }
        }
        assertTrue(foundRetagged,
                "Tier-2 replay must report exactly one retagged row");
        assertTrue(foundTransition,
                "Tier-2 replay must report the family transition with the original count");
        assertTrue(foundTuple,
                "Tier-2 replay must surface the per-tuple transition for the relevant verb");
    }

    @Test
    public void sidecarAbsentEmitsSidecarMarkerZero(@TempDir Path tmp)
            throws IOException {
        // Directory has trace_window_summary.csv (header only) but no
        // trace_window_classifier_inputs.csv — simulates a live run
        // where enableTraceFlowTupleDump was off. Replay must emit the
        // sidecar marker row with present=0 so downstream tooling can
        // distinguish "metadata tier unavailable" from "enabled but
        // empty".
        Path obs = tmp.resolve("observability");
        Files.createDirectories(obs);
        Files.write(obs.resolve("trace_window_summary.csv"),
                (buildPhase3Header() + "\n").getBytes(StandardCharsets.UTF_8));

        TraceReplay.Args args = new TraceReplay.Args();
        args.inputs.add(obs);
        args.preset = TraceSystemPreset.HDFS;
        args.system = "hdfs";
        args.outDir = tmp.resolve("replay-out");
        TraceReplay.run(args);

        Path changes = args.outDir.resolve("replay_classifier_changes.csv");
        List<String> lines = Files.readAllLines(changes);
        boolean foundAbsent = false;
        boolean foundTotalZero = false;
        boolean foundRetaggedZero = false;
        for (String line : lines) {
            if (line.endsWith(",sidecar,present,0")) {
                foundAbsent = true;
            }
            if (line.endsWith(",total,total_rows,0")) {
                foundTotalZero = true;
            }
            if (line.endsWith(",total,rows_retagged,0")) {
                foundRetaggedZero = true;
            }
        }
        assertTrue(foundAbsent,
                "sidecar_absent marker (present=0) must be emitted when trace_window_classifier_inputs.csv is missing");
        assertTrue(foundTotalZero,
                "zero total_rows marker must accompany the absent sidecar");
        assertTrue(foundRetaggedZero,
                "zero rows_retagged marker must accompany the absent sidecar");
    }

    @Test
    public void sidecarPresentButEmptyEmitsPresentOneTotalZero(
            @TempDir Path tmp)
            throws IOException {
        // Sidecar exists with only the CSV header — the live run had
        // enableTraceFlowTupleDump=true but produced zero tuples.
        // Replay must emit present=1 + total_rows=0 so downstream
        // tooling knows the tier WAS available, just empty.
        Path obs = tmp.resolve("observability");
        Files.createDirectories(obs);
        Files.write(obs.resolve("trace_window_summary.csv"),
                (buildPhase3Header() + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(obs.resolve("trace_window_classifier_inputs.csv"),
                ("round,test_packet_id,window_ordinal,comparison_stage,lane,"
                        + "protocol,rpc_service,rpc_method,message_type,"
                        + "message_kind,payload_type,current_family,count\n")
                                .getBytes(StandardCharsets.UTF_8));

        TraceReplay.Args args = new TraceReplay.Args();
        args.inputs.add(obs);
        args.preset = TraceSystemPreset.HDFS;
        args.system = "hdfs";
        args.outDir = tmp.resolve("replay-out");
        TraceReplay.run(args);

        Path changes = args.outDir.resolve("replay_classifier_changes.csv");
        List<String> lines = Files.readAllLines(changes);
        boolean foundPresent = false;
        boolean foundTotalZero = false;
        for (String line : lines) {
            if (line.endsWith(",sidecar,present,1")) {
                foundPresent = true;
            }
            if (line.endsWith(",total,total_rows,0")) {
                foundTotalZero = true;
            }
        }
        assertTrue(foundPresent,
                "sidecar_present marker (present=1) must be emitted when the sidecar exists, even with zero rows");
        assertTrue(foundTotalZero,
                "zero total_rows marker must accompany the empty-but-present sidecar");
    }

    @Test
    public void argsParserAcceptsFamilyMapProfileFlag() {
        TraceReplay.Args a = TraceReplay.Args.parse(new String[] {
                "--input-dir", "/tmp/x",
                "--preset", "HDFS",
                "--family-map-profile", "/tmp/foo.yaml",
        });
        assertNotNull(a.familyMapProfile);
        assertEquals("/tmp/foo.yaml", a.familyMapProfile.toString());
    }

    @Test
    public void argsParserRequiresInputDir() {
        assertThrows(IllegalArgumentException.class,
                () -> TraceReplay.Args
                        .parse(new String[] { "--preset", "HDFS" }));
    }

    @Test
    public void argsParserAcceptsThresholdOverrides() {
        TraceReplay.Args a = TraceReplay.Args.parse(new String[] {
                "--input-dir", "/tmp/x",
                "--preset", "HDFS",
                "--system", "hdfs",
                "--strong-threshold", "0.45",
                "--weak-threshold", "0.05",
                "--background-cap", "0.06",
        });
        assertEquals(1, a.inputs.size());
        assertSame(TraceSystemPreset.HDFS, a.preset);
        assertEquals("hdfs", a.system);
        assertNotNull(a.strongThreshold);
        assertEquals(0.45, a.strongThreshold, 1e-9);
        assertEquals(0.05, a.weakThreshold, 1e-9);
        assertEquals(0.06, a.backgroundCap, 1e-9);
    }

    private static String buildPhase3Header() {
        return String.join(",",
                "round", "test_packet_id", "window_ordinal", "comparison_stage",
                "total_messages", "total_all_three_count", "rolling_exclusive",
                "rolling_missing", "rolling_exclusive_fraction",
                "rolling_missing_fraction", "sim_oo_ro", "sim_ro_nn",
                "sim_baseline", "rolling_min_similarity",
                "rolling_divergence_margin", "window_has_enough_events",
                "window_sim_fired", "tri_diff_exclusive_fired",
                "tri_diff_missing_fired", "baseline_shared_count",
                "changed_message_count", "upgraded_boundary_event_count",
                "trace_evidence_strength", "support_gate_passed",
                "boundary_event_count_total",
                "boundary_event_count_index_resolved",
                "boundary_event_count_role_resolved",
                "boundary_event_count_role_ambiguous",
                "boundary_event_count_unresolved", "flow_total_old_old",
                "flow_total_rolling", "flow_total_new_new",
                "flow_explicit_id_rolling", "flow_fallback_rolling",
                "flow_grouping_failed_rolling",
                "flow_boundary_involved_rolling",
                "flow_role_ambiguous_boundary_rolling",
                "flow_unresolved_boundary_rolling",
                "flow_top_divergent_families",
                "flow_top_divergent_details_rolling", "support_class",
                "family_support_count", "flow_support_count",
                "baseline_flow_support_count",
                "background_family_support_count",
                "upgrade_critical_support_count", "family_jaccard_oo_ro",
                "family_jaccard_ro_nn", "family_jaccard_oo_nn",
                "family_weighted_sim_oo_ro", "family_weighted_sim_ro_nn",
                "family_weighted_sim_oo_nn", "flow_jaccard_oo_ro",
                "flow_jaccard_ro_nn", "flow_jaccard_oo_nn",
                "explicit_flow_jaccard_oo_ro", "explicit_flow_jaccard_ro_nn",
                "explicit_flow_jaccard_oo_nn", "order_similarity_oo_ro",
                "order_similarity_ro_nn", "order_similarity_oo_nn",
                "baseline_agreement_score", "rolling_divergence_score",
                "order_divergence_score", "background_share_rolling",
                "boundary_bonus_applied", "order_bonus_applied",
                "background_cap_applied", "composite_score",
                "rolling_exclusive_upgrade_critical_events",
                "dominant_supported_family", "dominant_divergent_family",
                "dominant_order_anomalous_family", "family_profile_label",
                "rolling_only_upgrade_critical_present", "firing_reasons");
    }

    private static String buildPhase3RowWithDivergentDetails(String stage,
            String supportClass, double composite, double baseline,
            double rolling, String originalStrength, String divergentDetails) {
        // Same column structure as buildPhase3Row but with a populated
        // flow_top_divergent_details_rolling column at position 39 (the
        // helper places the empty placeholder by default; this version
        // patches in a real value plus quotes if commas are present).
        // Easiest path: build a Phase-3 row, then replace the empty
        // value at index 39 with the supplied label string.
        String base = buildPhase3Row(stage, true, supportClass, composite,
                baseline, rolling, originalStrength, true, false);
        String[] cols = TraceReplay.parseCsv(base);
        cols[39] = divergentDetails == null ? "" : divergentDetails;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String c = cols[i];
            if (c.indexOf(',') >= 0 || c.indexOf('"') >= 0) {
                sb.append('"').append(c.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String buildPhase3Row(String stage, boolean fired,
            String supportClass, double composite, double baseline,
            double rolling, String originalStrength, boolean supportGate,
            boolean rollingOnlyUpgradeCritical) {
        // 76-column row matching buildPhase3Header() — defaults are zeros
        // for every numeric column except the ones we vary.
        StringBuilder sb = new StringBuilder();
        sb.append("0,0,0,").append(stage).append(','); // round, packet,
                                                       // ordinal, stage
        sb.append("100,5,3,1,0.03,0.01,0.5,0.5,0.5,0.5,0.0,"); // counts,
                                                               // fractions,
                                                               // sims
        sb.append(fired).append(",").append(fired).append(",").append(fired)
                .append(','); // window_has, sim_fired, exclusive_fired
        sb.append("false,17,0,0,").append(originalStrength).append(",")
                .append(supportGate);
        // Phase 0 boundary cols (5)
        sb.append(",10,5,5,0,0");
        // Phase 2 flow cols: 9 numeric + 2 string = 11 cols, all
        // separated by commas. Trailing ",,," closes both empty
        // string columns (flow_top_divergent_families and
        // flow_top_divergent_details_rolling) before the next field.
        sb.append(",4,4,4,2,2,0,1,0,0,,,");
        // Phase 3 cols (36): support_class + counts + similarities + composites
        sb.append(supportClass).append(",2,2,2,1,1,"); // support + 5 counts
        sb.append("0.5,0.5,0.5,0.5,0.5,0.5,"); // family jaccards
        sb.append("0.5,0.5,0.5,0.5,0.5,0.5,"); // flow + explicit-flow jaccards
        sb.append("0.5,0.5,0.5,"); // order sims
        sb.append(baseline).append(',').append(rolling).append(",0.5,0.0,"); // baseline/rolling/order/background-share
        sb.append("0.0,0.0,0.0,").append(composite).append(",0,"); // bonuses +
                                                                   // composite
                                                                   // + count
        sb.append(",,,,").append(rollingOnlyUpgradeCritical).append(','); // dominant
                                                                          // labels
                                                                          // +
                                                                          // flag
        // firing_reasons last column (may be empty)
        sb.append("");
        return sb.toString();
    }
}
