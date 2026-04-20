package org.zlab.upfuzz.fuzzingengine.replay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.zlab.net.tracker.classifier.ProtocolFamily;
import org.zlab.net.tracker.classifier.ProtocolFamilyClassifier;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.TraceSystemPreset;
import org.zlab.upfuzz.fuzzingengine.server.TraceSupportClass;
import org.zlab.upfuzz.fuzzingengine.server.TraceWindowGuidanceScorer;
import org.zlab.upfuzz.fuzzingengine.server.observability.TraceEvidenceStrength;
import org.zlab.upfuzz.fuzzingengine.trace.VersionAwareFamilyProfile;

/**
 * Phase 5 lightweight offline trace-guidance replay.
 *
 * <p>Reads {@code trace_window_summary.csv} files from one or more
 * {@code runner_result/observability/} directories and re-scores each
 * window under a chosen {@link TraceSystemPreset}, without launching a
 * full campaign. The replay is split into two explicit tiers per the
 * Phase 5 plan:
 *
 * <ul>
 * <li><b>Historical-artifact tier</b>: works on pre-Phase-3 CSVs (Apr15 /
 *     Apr16) that only carry the Phase 0/1 columns. Strength is
 *     re-classified from the stored support-gate, changed-message and
 *     upgraded-boundary fields under the new policy thresholds, and the
 *     summary calls out that metadata-dependent components are unavailable
 *     in the source CSV.</li>
 * <li><b>Post-instrumentation tier</b>: works on Phase-3+ CSVs that carry
 *     the full composite scorer columns ({@code support_class},
 *     {@code composite_score}, {@code baseline_agreement_score},
 *     {@code rolling_divergence_score}, etc.). Strength is recomputed by
 *     the same {@link TraceWindowGuidanceScorer.Weights} bundle the live
 *     server uses, so threshold and weight changes can be exercised
 *     without launching a campaign.</li>
 * </ul>
 *
 * <p>Each input CSV is auto-classified by column count: rows with the full
 * Phase-3 column header are treated as post-instrumentation; rows with
 * only the Phase 0/1 column header are treated as historical.
 *
 * <p>Output: a single {@code replay_summary.csv} per replay run, plus
 * a {@code replay_per_host.csv} with per-host aggregates. Use
 * {@code --out-dir} to choose the output directory; defaults to
 * {@code <input>/replay_<preset>/}.
 *
 * <p>CLI:
 * <pre>
 *   java -cp upfuzz.jar org.zlab.upfuzz.fuzzingengine.replay.TraceReplay \
 *       --input-dir /path/to/runner_result/observability \
 *       --preset HDFS \
 *       --tier post-instrumentation \
 *       --out-dir /tmp/replay
 * </pre>
 *
 * <p>Multiple input directories may be supplied with repeated
 * {@code --input-dir} flags; per-host aggregation uses the directory
 * basename as the host identifier when no {@code --host} flag is given.
 */
public final class TraceReplay {

    private TraceReplay() {
    }

    /** CLI entry. */
    public static void main(String[] args) throws IOException {
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            System.err.println(usage());
            System.exit(2);
            return;
        }
        if (parsed.help) {
            System.out.println(usage());
            return;
        }
        run(parsed);
    }

    public static String usage() {
        return String.join("\n",
                "Usage: TraceReplay --input-dir <dir> [--input-dir <dir> ...]",
                "                   [--preset CASSANDRA|HDFS|HBASE|GENERIC|AUTO]",
                "                   [--system cassandra|hdfs|hbase]",
                "                   [--tier auto|historical|post-instrumentation]",
                "                   [--out-dir <dir>]",
                "                   [--family-map-profile <yaml>]",
                "                   [--strong-threshold <double>] [--weak-threshold <double>]",
                "                   [--min-baseline-agreement <double>]",
                "                   [--min-rolling-divergence <double>]",
                "                   [--background-cap <double>] [--boundary-bonus <double>]",
                "                   [--order-bonus-cap <double>]",
                "                   [-h | --help]",
                "",
                "Each --input-dir must point at a runner observability/ directory",
                "(or any directory containing trace_window_summary.csv). When a",
                "trace_window_classifier_inputs.csv sidecar (Phase-5 opt-in dump)",
                "is present alongside the summary, the replay re-classifies each",
                "raw tuple under --family-map-profile (when provided) and emits a",
                "replay_classifier_changes.csv detailing which tuples would shift",
                "family. When --preset is AUTO and --system is supplied, the",
                "system-specific preset is applied; otherwise GENERIC is used.",
                "CLI threshold flags override the preset values for the run.");
    }

    public static void run(Args args) throws IOException {
        TraceSystemPreset preset = args.preset.resolve(args.system);
        Config.Configuration synthetic = new Config.Configuration();
        synthetic.system = args.system;
        // Apply the preset onto the synthetic config so Weights.fromConfig
        // sees the resolved values.
        preset.applyTo(synthetic);
        applyOverrides(synthetic, args);
        TraceWindowGuidanceScorer.Weights weights = TraceWindowGuidanceScorer.Weights
                .fromConfig(synthetic);

        // Phase 5 Tier-2 wiring: when the user supplies
        // --family-map-profile, register the YAML profile as the active
        // long-tail override on the live classifier. Re-classification of
        // the per-tuple sidecar (trace_window_classifier_inputs.csv) then
        // sees the override. We always restore the previous registration
        // at the end of run() so concurrent / sequential replay
        // invocations in the same JVM do not leak override state.
        ProtocolFamilyClassifier.LongTailOverride savedOverride = ProtocolFamilyClassifier
                .getLongTailOverride();
        VersionAwareFamilyProfile loadedProfile = null;
        if (args.familyMapProfile != null) {
            loadedProfile = VersionAwareFamilyProfile
                    .loadAndRegister(args.familyMapProfile);
        }

        Path outDir = args.outDir != null
                ? args.outDir
                : args.inputs.get(0).resolve(
                        "replay_" + preset.name().toLowerCase(Locale.ROOT));
        Files.createDirectories(outDir);

        ReplayResult result = new ReplayResult();
        try {
            for (Path inDir : args.inputs) {
                Path csvPath = inDir.resolve("trace_window_summary.csv");
                if (!Files.isRegularFile(csvPath)) {
                    System.err.println(
                            "[WARN] missing trace_window_summary.csv under "
                                    + inDir);
                    continue;
                }
                String host = args.hostOverride != null
                        ? args.hostOverride
                        : inferHostName(inDir);
                HostAggregate ha = result.hostFor(host);
                replayCsv(csvPath, ha, host, weights, args.tier);

                // Tier-2 metadata coverage (read-only stat).
                Path metaPath = inDir.resolve("trace_metadata_coverage.csv");
                if (Files.isRegularFile(metaPath)) {
                    ha.metadataCoverage = readMetadataCoverage(metaPath);
                }

                // Tier-2 profile-aware re-classification: when the
                // server emitted trace_window_classifier_inputs.csv
                // (Phase-5 opt-in dump) AND a profile is loaded, walk
                // the per-tuple rows and re-classify each (rpcService,
                // rpcMethod, messageType, ...) tuple. Records every
                // tuple whose new family differs from the recorded
                // currentFamily so the replay output explains exactly
                // what the profile would have changed at fuzzing time.
                //
                // ha.classifierSidecarPresent distinguishes two states
                // the reviewer called out:
                // - file absent => metadata tier unavailable for
                // this host (dump was off at run
                // time).
                // - file present => metadata tier was enabled;
                // rowsRetagged may still be zero
                // if no tuple matched the profile.
                Path tuplesPath = inDir
                        .resolve("trace_window_classifier_inputs.csv");
                if (Files.isRegularFile(tuplesPath)) {
                    ha.classifierSidecarPresent = true;
                    ha.classifierTupleStats = replayClassifierInputs(
                            tuplesPath);
                } else {
                    ha.classifierSidecarPresent = false;
                }
            }

            writeSummary(result, outDir, preset, args, weights);
            writeClassifierChanges(result, outDir, loadedProfile);
            System.out.println("[OK] Wrote replay summary to " + outDir);
        } finally {
            // Restore the previously-registered override so sequential
            // replay invocations in the same JVM do not leak state.
            ProtocolFamilyClassifier.setLongTailOverride(savedOverride);
        }
    }

    // -------------------------------------------------------------------
    // Replay logic.
    // -------------------------------------------------------------------

    static void replayCsv(Path csvPath, HostAggregate ha, String hostLabel,
            TraceWindowGuidanceScorer.Weights weights, Tier requestedTier)
            throws IOException {
        try (BufferedReader br = Files.newBufferedReader(csvPath,
                StandardCharsets.UTF_8)) {
            String header = br.readLine();
            if (header == null) {
                return;
            }
            CsvSchema schema = CsvSchema.detect(header);
            ha.detectedTier = schema.tier;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] cols = parseCsv(line);
                if (cols.length < schema.minColumns) {
                    continue;
                }
                Row row = schema.parse(cols);
                if (row == null) {
                    continue;
                }
                ha.totalRows++;
                TraceEvidenceStrength original = row.originalStrength;
                TraceEvidenceStrength replay = recomputeStrength(row, weights,
                        schema.tier);
                String routing = recommendedRoutingFor(replay);
                ha.recordOriginal(original);
                ha.recordReplay(replay);
                if (original != replay) {
                    ha.transitionCounter
                            .merge(original.name() + "->" + replay.name(), 1,
                                    Integer::sum);
                }
                if (row.dominantSupportedFamily != null
                        && !row.dominantSupportedFamily.isEmpty()) {
                    ha.topSupportedFamilies
                            .merge(row.dominantSupportedFamily, 1,
                                    Integer::sum);
                }
                if (row.dominantDivergentFamily != null
                        && !row.dominantDivergentFamily.isEmpty()) {
                    ha.topDivergentFamilies
                            .merge(row.dominantDivergentFamily, 1,
                                    Integer::sum);
                }
                if (row.dominantOrderAnomalousFamily != null
                        && !row.dominantOrderAnomalousFamily.isEmpty()) {
                    ha.topOrderAnomalousFamilies
                            .merge(row.dominantOrderAnomalousFamily, 1,
                                    Integer::sum);
                }
                accumulateServiceMethodLabels(
                        row.flowTopDivergentDetailsRolling, ha);
                ha.perWindow.add(
                        new PerWindowRecord(hostLabel, row, replay, routing));
            }
        }
        if (requestedTier != Tier.AUTO && ha.detectedTier != requestedTier) {
            ha.tierMismatch = true;
        }
    }

    /**
     * Re-evaluate the strength tier for a parsed CSV row under the
     * supplied {@link TraceWindowGuidanceScorer.Weights}.
     *
     * <p>For post-instrumentation rows with the full composite components,
     * this applies the same gate the live scorer uses: strong requires
     * support &ge; FAMILY_BACKED, baseline agreement &ge;
     * minBaselineAgreementForStrong, rolling divergence &ge;
     * minRollingDivergenceForStrong, composite &ge; strongScoreThreshold,
     * and a mixed-version stage. For historical rows the inputs are
     * sparser, so we fall back to a conservative re-derivation:
     * support_gate_passed + (boundary or changed-message) corroboration
     * + same stage gate.
     */
    static TraceEvidenceStrength recomputeStrength(Row row,
            TraceWindowGuidanceScorer.Weights w, Tier tier) {
        if (!row.windowFired) {
            return TraceEvidenceStrength.NONE;
        }
        boolean mixedStage = isMixedVersionStage(row.comparisonStage);
        if (tier == Tier.POST_INSTRUMENTATION) {
            TraceSupportClass support = parseSupport(row.supportClass);
            if (support == TraceSupportClass.UNSUPPORTED) {
                return row.rollingOnlyUpgradeCriticalPresent
                        ? TraceEvidenceStrength.UNSUPPORTED_BUT_REPEATABLE
                        : TraceEvidenceStrength.UNSUPPORTED;
            }
            if (support == TraceSupportClass.BACKGROUND_ONLY) {
                return TraceEvidenceStrength.WEAK;
            }
            if (!mixedStage) {
                return TraceEvidenceStrength.WEAK;
            }
            boolean strongOk = support.atLeast(TraceSupportClass.FAMILY_BACKED)
                    && row.baselineAgreementScore >= w.minBaselineAgreementForStrong
                    && row.rollingDivergenceScore >= w.minRollingDivergenceForStrong
                    && row.compositeScore >= w.strongScoreThreshold;
            return strongOk ? TraceEvidenceStrength.STRONG
                    : TraceEvidenceStrength.WEAK;
        }
        // Historical-artifact tier: conservative re-derivation.
        if (!row.supportGatePassed) {
            return TraceEvidenceStrength.UNSUPPORTED;
        }
        boolean corroborated = row.upgradedBoundaryEventCount > 0
                || row.changedMessageCount > 0;
        if (mixedStage && corroborated) {
            return TraceEvidenceStrength.STRONG;
        }
        return TraceEvidenceStrength.WEAK;
    }

    /**
     * Recommend the queue lane for a replayed window's strength. Mirrors the
     * Phase 4 admission contract: STRONG → MAIN_EXPLOIT;
     * WEAK / UNSUPPORTED_BUT_REPEATABLE → SHADOW_EVAL; otherwise BRANCH_ONLY.
     */
    static String recommendedRoutingFor(TraceEvidenceStrength s) {
        if (s == null) {
            return "BRANCH_ONLY";
        }
        switch (s) {
        case STRONG:
            return "MAIN_EXPLOIT";
        case WEAK:
        case UNSUPPORTED_BUT_REPEATABLE:
            return "SHADOW_EVAL";
        case UNSUPPORTED:
        case NONE:
        default:
            return "BRANCH_ONLY";
        }
    }

    /**
     * Parse the {@code flow_top_divergent_details_rolling} column and credit
     * each {@code FAMILY|label} tuple into the host-level service / method
     * frequency map. Format produced by
     * {@code FuzzingServer.formatTopDivergentDetails}:
     * {@code FAMILY:label=count|label=count;FAMILY:label=count}.
     * Empty / null inputs are no-ops.
     */
    static void accumulateServiceMethodLabels(String column, HostAggregate ha) {
        if (column == null || column.isEmpty()) {
            return;
        }
        for (String familyChunk : column.split(";")) {
            int colon = familyChunk.indexOf(':');
            if (colon <= 0 || colon >= familyChunk.length() - 1) {
                continue;
            }
            String family = familyChunk.substring(0, colon).trim();
            String labelChunk = familyChunk.substring(colon + 1);
            for (String labelEq : labelChunk.split("\\|")) {
                int eq = labelEq.lastIndexOf('=');
                if (eq <= 0 || eq >= labelEq.length() - 1) {
                    continue;
                }
                String label = labelEq.substring(0, eq).trim();
                String countStr = labelEq.substring(eq + 1).trim();
                long count;
                try {
                    count = Long.parseLong(countStr);
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (label.isEmpty() || count <= 0) {
                    continue;
                }
                ha.serviceMethodLabelCounts.merge(family + "|" + label, count,
                        Long::sum);
            }
        }
    }

    private static boolean isMixedVersionStage(String stage) {
        if (stage == null) {
            return false;
        }
        String upper = stage.toUpperCase(Locale.ROOT);
        return upper.startsWith("POST_STAGE")
                || upper.startsWith("POST_FINAL_STAGE")
                || upper.startsWith("FAULT_RECOVERY");
    }

    private static TraceSupportClass parseSupport(String s) {
        if (s == null || s.isEmpty()) {
            return TraceSupportClass.UNSUPPORTED;
        }
        try {
            return TraceSupportClass.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return TraceSupportClass.UNSUPPORTED;
        }
    }

    // -------------------------------------------------------------------
    // CSV parsing.
    // -------------------------------------------------------------------

    /**
     * Schema descriptor: maps column names to indices and tracks which tier
     * the CSV represents. A header with at least the Phase-3 columns is
     * treated as post-instrumentation; otherwise historical.
     */
    static final class CsvSchema {
        final Tier tier;
        final int minColumns;
        final Map<String, Integer> idx;

        private CsvSchema(Tier tier, int minColumns, Map<String, Integer> idx) {
            this.tier = tier;
            this.minColumns = minColumns;
            this.idx = idx;
        }

        static CsvSchema detect(String headerLine) {
            String[] cols = parseCsv(headerLine);
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < cols.length; i++) {
                idx.put(cols[i].trim(), i);
            }
            Tier tier = idx.containsKey("composite_score")
                    && idx.containsKey("support_class")
                    && idx.containsKey("baseline_agreement_score")
                            ? Tier.POST_INSTRUMENTATION
                            : Tier.HISTORICAL;
            int minCols = tier == Tier.POST_INSTRUMENTATION ? 60 : 24;
            return new CsvSchema(tier, minCols, idx);
        }

        Row parse(String[] cols) {
            Row r = new Row();
            r.round = colLong(cols, "round", 0L);
            r.testPacketId = colInt(cols, "test_packet_id", 0);
            r.windowOrdinal = colInt(cols, "window_ordinal", 0);
            r.comparisonStage = colString(cols, "comparison_stage");
            // Fired bookkeeping: include all three live "fired" columns so
            // missing-only fired windows are not silently dropped from
            // replay aggregation. The live admission contract still treats
            // missing-only as non-admitting (Phase 1 hotfix), which the
            // recomputeStrength historical path enforces via the support
            // gate; replay only ensures the window is visible.
            r.windowSimFired = colBoolean(cols, "window_sim_fired", false);
            r.triDiffExclusiveFired = colBoolean(cols,
                    "tri_diff_exclusive_fired", false);
            r.triDiffMissingFired = colBoolean(cols,
                    "tri_diff_missing_fired", false);
            r.windowFired = r.windowSimFired || r.triDiffExclusiveFired
                    || r.triDiffMissingFired;
            r.supportGatePassed = colBoolean(cols, "support_gate_passed",
                    false);
            r.upgradedBoundaryEventCount = colInt(cols,
                    "upgraded_boundary_event_count", 0);
            r.changedMessageCount = colInt(cols, "changed_message_count", 0);
            r.originalStrength = parseStrength(
                    colString(cols, "trace_evidence_strength"));
            r.supportClass = colString(cols, "support_class");
            r.compositeScore = colDouble(cols, "composite_score", 0.0);
            r.baselineAgreementScore = colDouble(cols,
                    "baseline_agreement_score", 0.0);
            r.rollingDivergenceScore = colDouble(cols,
                    "rolling_divergence_score", 0.0);
            r.dominantSupportedFamily = colString(cols,
                    "dominant_supported_family");
            r.dominantDivergentFamily = colString(cols,
                    "dominant_divergent_family");
            r.dominantOrderAnomalousFamily = colString(cols,
                    "dominant_order_anomalous_family");
            r.flowTopDivergentDetailsRolling = colString(cols,
                    "flow_top_divergent_details_rolling");
            r.rollingOnlyUpgradeCriticalPresent = colBoolean(cols,
                    "rolling_only_upgrade_critical_present", false);
            return r;
        }

        private String colString(String[] cols, String name) {
            Integer i = idx.get(name);
            if (i == null || i >= cols.length) {
                return "";
            }
            return cols[i];
        }

        private boolean colBoolean(String[] cols, String name, boolean def) {
            String s = colString(cols, name);
            if (s == null || s.isEmpty()) {
                return def;
            }
            return "true".equalsIgnoreCase(s.trim());
        }

        private int colInt(String[] cols, String name, int def) {
            String s = colString(cols, name);
            if (s == null || s.isEmpty()) {
                return def;
            }
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ex) {
                return def;
            }
        }

        private long colLong(String[] cols, String name, long def) {
            String s = colString(cols, name);
            if (s == null || s.isEmpty()) {
                return def;
            }
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ex) {
                return def;
            }
        }

        private double colDouble(String[] cols, String name, double def) {
            String s = colString(cols, name);
            if (s == null || s.isEmpty()) {
                return def;
            }
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ex) {
                return def;
            }
        }
    }

    static TraceEvidenceStrength parseStrength(String s) {
        if (s == null || s.isEmpty()) {
            return TraceEvidenceStrength.NONE;
        }
        try {
            return TraceEvidenceStrength
                    .valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return TraceEvidenceStrength.NONE;
        }
    }

    /**
     * Minimal CSV splitter that handles the quoted-field variant produced
     * by {@code WindowTriggerRow.csvEscape}. Does not support multi-line
     * fields (the writer never emits them).
     */
    static String[] parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else if (c == '"' && cur.length() == 0) {
                    inQuotes = true;
                } else {
                    cur.append(c);
                }
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    // -------------------------------------------------------------------
    // Per-host aggregation.
    // -------------------------------------------------------------------

    static String inferHostName(Path inputDir) {
        Path p = inputDir.toAbsolutePath();
        // Prefer the parent of "observability" — usually the
        // runner_result directory's grandparent (the host dir).
        if ("observability".equals(p.getFileName().toString())) {
            Path runnerResult = p.getParent();
            if (runnerResult != null) {
                Path host = runnerResult.getParent();
                if (host != null) {
                    return host.getFileName().toString();
                }
            }
        }
        return p.getFileName().toString();
    }

    /**
     * Phase 5 Tier-2: walk {@code trace_window_classifier_inputs.csv} and
     * re-classify each per-tuple row through the live
     * {@link ProtocolFamilyClassifier}. The currently-active long-tail
     * override (set via {@link VersionAwareFamilyProfile#loadAndRegister})
     * is consulted as part of the lookup, so tuples the live run
     * classified as UNKNOWN may now resolve to a more specific family if
     * the loaded profile covers their {@code (rpcService, rpcMethod)} or
     * {@code messageType}. Returns aggregate transition stats.
     */
    static ClassifierTupleReplay replayClassifierInputs(Path tuplesCsv)
            throws IOException {
        ClassifierTupleReplay stats = new ClassifierTupleReplay();
        try (BufferedReader br = Files.newBufferedReader(tuplesCsv,
                StandardCharsets.UTF_8)) {
            String header = br.readLine();
            if (header == null) {
                return stats;
            }
            String[] hdr = parseCsv(header);
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < hdr.length; i++) {
                idx.put(hdr[i].trim(), i);
            }
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] cols = parseCsv(line);
                String protocol = colString(cols, idx, "protocol");
                String rpcService = colString(cols, idx, "rpc_service");
                String rpcMethod = colString(cols, idx, "rpc_method");
                String messageType = colString(cols, idx, "message_type");
                String messageKind = colString(cols, idx, "message_kind");
                String payloadType = colString(cols, idx, "payload_type");
                String oldFamily = colString(cols, idx, "current_family");
                long count;
                try {
                    count = Long.parseLong(
                            colString(cols, idx, "count").trim());
                } catch (NumberFormatException ex) {
                    count = 0L;
                }
                stats.totalRows++;
                ProtocolFamily replayFamily = ProtocolFamilyClassifier
                        .classify(emptyToNull(protocol),
                                emptyToNull(messageType),
                                emptyToNull(rpcService),
                                emptyToNull(rpcMethod),
                                emptyToNull(messageKind),
                                emptyToNull(payloadType));
                String newFamily = replayFamily == null
                        ? ProtocolFamily.UNKNOWN.name()
                        : replayFamily.name();
                if (!newFamily.equals(oldFamily)) {
                    stats.rowsRetagged++;
                    String transition = (oldFamily == null
                            || oldFamily.isEmpty() ? "UNKNOWN" : oldFamily)
                            + "->" + newFamily;
                    stats.familyTransitions.merge(transition, count,
                            Long::sum);
                    String tupleLabel = transition + "|"
                            + (rpcService.isEmpty() ? "?" : rpcService) + ":"
                            + (rpcMethod.isEmpty() ? "?" : rpcMethod) + "@"
                            + (messageType.isEmpty() ? "?" : messageType);
                    stats.tupleTransitions.merge(tupleLabel, count, Long::sum);
                }
            }
        }
        return stats;
    }

    private static String emptyToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String colString(String[] cols, Map<String, Integer> idx,
            String name) {
        Integer i = idx.get(name);
        if (i == null || i >= cols.length) {
            return "";
        }
        return cols[i];
    }

    static MetadataCoverage readMetadataCoverage(Path csvPath)
            throws IOException {
        MetadataCoverage cov = new MetadataCoverage();
        try (BufferedReader br = Files.newBufferedReader(csvPath,
                StandardCharsets.UTF_8)) {
            String header = br.readLine();
            if (header == null) {
                return cov;
            }
            String[] hdr = parseCsv(header);
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < hdr.length; i++) {
                idx.put(hdr[i].trim(), i);
            }
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] cols = parseCsv(line);
                cov.rows++;
                cov.rpcServicePresent += longValue(cols, idx,
                        "rpc_service_present", 0L);
                cov.rpcMethodPresent += longValue(cols, idx,
                        "rpc_method_present", 0L);
                cov.messageKindPresent += longValue(cols, idx,
                        "message_kind_present", 0L);
                cov.logicalMessageIdPresent += longValue(cols, idx,
                        "logical_message_id_present",
                        0L);
                cov.deliveryIdPresent += longValue(cols, idx,
                        "delivery_id_present", 0L);
            }
        }
        return cov;
    }

    private static long longValue(String[] cols, Map<String, Integer> idx,
            String name, long def) {
        Integer i = idx.get(name);
        if (i == null || i >= cols.length) {
            return def;
        }
        try {
            return Long.parseLong(cols[i].trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    // -------------------------------------------------------------------
    // Output writers.
    // -------------------------------------------------------------------

    static void writeSummary(ReplayResult result, Path outDir,
            TraceSystemPreset preset, Args args,
            TraceWindowGuidanceScorer.Weights weights) throws IOException {
        Path summaryPath = outDir.resolve("replay_summary.csv");
        Path perHostPath = outDir.resolve("replay_per_host.csv");
        Path topFamiliesPath = outDir.resolve("replay_top_families.csv");

        try (PrintWriter pw = new PrintWriter(
                Files.newBufferedWriter(summaryPath,
                        StandardCharsets.UTF_8))) {
            pw.println("preset,system,tier,strong_threshold,weak_threshold,"
                    + "min_baseline_agreement,min_rolling_divergence,"
                    + "background_cap,boundary_bonus,order_bonus_cap,"
                    + "hosts,total_rows,strong_replay,weak_replay,unsupported_replay,"
                    + "unsupported_repeatable_replay,none_replay,"
                    + "main_exploit_recommended,shadow_eval_recommended,"
                    + "branch_only_recommended");
            ReplayTotals tot = result.totals();
            int main = tot.replayCounts
                    .getOrDefault(TraceEvidenceStrength.STRONG, 0);
            int shadow = tot.replayCounts
                    .getOrDefault(TraceEvidenceStrength.WEAK, 0)
                    + tot.replayCounts.getOrDefault(
                            TraceEvidenceStrength.UNSUPPORTED_BUT_REPEATABLE,
                            0);
            int branchOnly = tot.replayCounts
                    .getOrDefault(TraceEvidenceStrength.NONE, 0)
                    + tot.replayCounts
                            .getOrDefault(TraceEvidenceStrength.UNSUPPORTED, 0);
            pw.printf(Locale.ROOT,
                    "%s,%s,%s,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                    preset.name(), args.system == null ? "" : args.system,
                    detectTierLabel(result),
                    weights.strongScoreThreshold, weights.weakScoreThreshold,
                    weights.minBaselineAgreementForStrong,
                    weights.minRollingDivergenceForStrong,
                    weights.backgroundCap, weights.boundaryBonus,
                    weights.orderBonusCap,
                    result.hosts.size(), tot.totalRows,
                    tot.replayCounts.getOrDefault(TraceEvidenceStrength.STRONG,
                            0),
                    tot.replayCounts.getOrDefault(TraceEvidenceStrength.WEAK,
                            0),
                    tot.replayCounts
                            .getOrDefault(TraceEvidenceStrength.UNSUPPORTED, 0),
                    tot.replayCounts.getOrDefault(
                            TraceEvidenceStrength.UNSUPPORTED_BUT_REPEATABLE,
                            0),
                    tot.replayCounts.getOrDefault(TraceEvidenceStrength.NONE,
                            0),
                    main, shadow, branchOnly);
        }

        try (PrintWriter pw = new PrintWriter(
                Files.newBufferedWriter(perHostPath,
                        StandardCharsets.UTF_8))) {
            pw.println("host,tier,total_rows,strong_replay,weak_replay,"
                    + "unsupported_replay,unsupported_repeatable_replay,none_replay,"
                    + "strong_original,weak_original,unsupported_original,"
                    + "metadata_rows,rpc_service_present,rpc_method_present,"
                    + "message_kind_present,logical_message_id_present,delivery_id_present");
            for (Map.Entry<String, HostAggregate> e : result.hosts.entrySet()) {
                HostAggregate ha = e.getValue();
                MetadataCoverage cov = ha.metadataCoverage == null
                        ? new MetadataCoverage()
                        : ha.metadataCoverage;
                pw.printf(Locale.ROOT,
                        "%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                        e.getKey(),
                        ha.detectedTier == null ? "AUTO"
                                : ha.detectedTier.name(),
                        ha.totalRows,
                        ha.replayCounts
                                .getOrDefault(TraceEvidenceStrength.STRONG, 0),
                        ha.replayCounts.getOrDefault(TraceEvidenceStrength.WEAK,
                                0),
                        ha.replayCounts.getOrDefault(
                                TraceEvidenceStrength.UNSUPPORTED, 0),
                        ha.replayCounts.getOrDefault(
                                TraceEvidenceStrength.UNSUPPORTED_BUT_REPEATABLE,
                                0),
                        ha.replayCounts.getOrDefault(TraceEvidenceStrength.NONE,
                                0),
                        ha.originalCounts
                                .getOrDefault(TraceEvidenceStrength.STRONG, 0),
                        ha.originalCounts
                                .getOrDefault(TraceEvidenceStrength.WEAK, 0),
                        ha.originalCounts.getOrDefault(
                                TraceEvidenceStrength.UNSUPPORTED, 0),
                        cov.rows, cov.rpcServicePresent, cov.rpcMethodPresent,
                        cov.messageKindPresent, cov.logicalMessageIdPresent,
                        cov.deliveryIdPresent);
            }
        }

        try (PrintWriter pw = new PrintWriter(
                Files.newBufferedWriter(topFamiliesPath,
                        StandardCharsets.UTF_8))) {
            pw.println("category,family,occurrences");
            ReplayTotals tot = result.totals();
            writeTopMap(pw, "supported", tot.topSupportedFamilies, 25);
            writeTopMap(pw, "divergent", tot.topDivergentFamilies, 25);
            writeTopMap(pw, "order_anomalous", tot.topOrderAnomalousFamilies,
                    25);
        }

        // Per-window CSV: one row per scored window, regardless of the
        // detected tier. Carries enough state for follow-up analysis to
        // bucket windows by routing recommendation, comparison stage, or
        // strength transition without re-loading the source CSV.
        Path perWindowPath = outDir.resolve("replay_per_window.csv");
        try (PrintWriter pw = new PrintWriter(
                Files.newBufferedWriter(perWindowPath,
                        StandardCharsets.UTF_8))) {
            pw.println("host,round,test_packet_id,window_ordinal,"
                    + "comparison_stage,window_sim_fired,"
                    + "tri_diff_exclusive_fired,tri_diff_missing_fired,"
                    + "original_strength,replay_strength,recommended_routing,"
                    + "support_class,composite_score,baseline_agreement_score,"
                    + "rolling_divergence_score,upgraded_boundary_event_count,"
                    + "changed_message_count,dominant_supported_family,"
                    + "dominant_divergent_family,dominant_order_anomalous_family,"
                    + "strength_changed");
            for (HostAggregate ha : result.hosts.values()) {
                for (PerWindowRecord rec : ha.perWindow) {
                    pw.printf(Locale.ROOT,
                            "%s,%d,%d,%d,%s,%s,%s,%s,%s,%s,%s,%s,%.4f,%.4f,%.4f,%d,%d,%s,%s,%s,%s%n",
                            csvEscape(rec.host), rec.round, rec.testPacketId,
                            rec.windowOrdinal,
                            csvEscape(rec.comparisonStage),
                            rec.windowSimFired, rec.triDiffExclusiveFired,
                            rec.triDiffMissingFired,
                            rec.originalStrength == null
                                    ? TraceEvidenceStrength.NONE.name()
                                    : rec.originalStrength.name(),
                            rec.replayStrength == null
                                    ? TraceEvidenceStrength.NONE.name()
                                    : rec.replayStrength.name(),
                            csvEscape(rec.recommendedRouting),
                            csvEscape(rec.supportClass), rec.compositeScore,
                            rec.baselineAgreementScore,
                            rec.rollingDivergenceScore,
                            rec.upgradedBoundaryEventCount,
                            rec.changedMessageCount,
                            csvEscape(rec.dominantSupportedFamily),
                            csvEscape(rec.dominantDivergentFamily),
                            csvEscape(rec.dominantOrderAnomalousFamily),
                            rec.originalStrength != rec.replayStrength);
                }
            }
        }

        // Per-system top service / method details aggregate. Mined from
        // the Phase-2 flow_top_divergent_details_rolling column. One row
        // per (family, label) tuple sorted by descending occurrences.
        Path topServiceMethodPath = outDir
                .resolve("replay_top_service_methods.csv");
        try (PrintWriter pw = new PrintWriter(
                Files.newBufferedWriter(topServiceMethodPath,
                        StandardCharsets.UTF_8))) {
            pw.println("family,label,occurrences");
            Map<String, Long> totals = new HashMap<>();
            for (HostAggregate ha : result.hosts.values()) {
                ha.serviceMethodLabelCounts.forEach(
                        (k, v) -> totals.merge(k, v, Long::sum));
            }
            totals.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(50)
                    .forEach(e -> {
                        String key = e.getKey();
                        int sep = key.indexOf('|');
                        String family = sep > 0 ? key.substring(0, sep) : "";
                        String label = sep > 0 ? key.substring(sep + 1) : key;
                        pw.printf(Locale.ROOT, "%s,%s,%d%n",
                                csvEscape(family), csvEscape(label),
                                e.getValue());
                    });
        }
    }

    /**
     * Phase 5 Tier-2 output: emit per-host classifier-input replay stats.
     * Always emitted (even when no profile is loaded or no sidecar was
     * present) so the file's existence + body unambiguously communicates
     * what happened: empty body means "no Phase-5 sidecar found / no
     * tuples re-classified"; populated body means the listed tuples
     * shifted family under the loaded profile.
     */
    static void writeClassifierChanges(ReplayResult result, Path outDir,
            VersionAwareFamilyProfile loadedProfile) throws IOException {
        Path summaryPath = outDir.resolve("replay_classifier_changes.csv");
        try (PrintWriter pw = new PrintWriter(
                Files.newBufferedWriter(summaryPath,
                        StandardCharsets.UTF_8))) {
            pw.printf(Locale.ROOT,
                    "# profile_id=%s, profile_entries=%d (svc/method=%d, messageType=%d)%n",
                    loadedProfile == null ? "<none>" : loadedProfile.id(),
                    loadedProfile == null ? 0 : loadedProfile.size(),
                    loadedProfile == null ? 0
                            : loadedProfile.serviceMethodEntryCount(),
                    loadedProfile == null ? 0
                            : loadedProfile.messageTypeEntryCount());
            pw.println("host,scope,key,count");
            for (Map.Entry<String, HostAggregate> e : result.hosts.entrySet()) {
                HostAggregate ha = e.getValue();
                String host = e.getKey();
                // Phase-5 disambiguation of the metadata tier state.
                // Emitted on EVERY host regardless of whether the
                // sidecar existed, so downstream tooling can branch on
                // sidecar_present without having to infer it from the
                // absence of other rows.
                pw.printf(Locale.ROOT, "%s,sidecar,present,%d%n",
                        csvEscape(host),
                        ha.classifierSidecarPresent ? 1L : 0L);
                if (!ha.classifierSidecarPresent
                        || ha.classifierTupleStats == null) {
                    // Metadata tier unavailable (dump was off during the
                    // live run): emit a zero-filled totals row so every
                    // host keeps the same schema, and skip the
                    // transitions block.
                    pw.printf(Locale.ROOT, "%s,total,total_rows,0%n",
                            csvEscape(host));
                    pw.printf(Locale.ROOT, "%s,total,rows_retagged,0%n",
                            csvEscape(host));
                    continue;
                }
                ClassifierTupleReplay s = ha.classifierTupleStats;
                pw.printf(Locale.ROOT, "%s,total,total_rows,%d%n",
                        csvEscape(host), s.totalRows);
                pw.printf(Locale.ROOT, "%s,total,rows_retagged,%d%n",
                        csvEscape(host), s.rowsRetagged);
                s.familyTransitions.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue(),
                                a.getValue()))
                        .limit(50)
                        .forEach(t -> pw.printf(Locale.ROOT,
                                "%s,family_transition,%s,%d%n",
                                csvEscape(host), csvEscape(t.getKey()),
                                t.getValue()));
                s.tupleTransitions.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue(),
                                a.getValue()))
                        .limit(50)
                        .forEach(t -> pw.printf(Locale.ROOT,
                                "%s,tuple_transition,%s,%d%n",
                                csvEscape(host), csvEscape(t.getKey()),
                                t.getValue()));
            }
        }
    }

    private static String detectTierLabel(ReplayResult result) {
        Set<Tier> tiers = result.hosts.values().stream()
                .map(ha -> ha.detectedTier)
                .filter(t -> t != null)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (tiers.isEmpty()) {
            return "AUTO";
        }
        if (tiers.size() == 1) {
            return tiers.iterator().next().name();
        }
        return tiers.stream().map(Enum::name).collect(Collectors.joining("|"));
    }

    private static void writeTopMap(PrintWriter pw, String category,
            Map<String, Integer> counts, int limit) {
        if (counts.isEmpty()) {
            return;
        }
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .forEach(e -> pw.printf(Locale.ROOT, "%s,%s,%d%n", category,
                        csvEscape(e.getKey()), e.getValue()));
    }

    private static String csvEscape(String f) {
        if (f == null) {
            return "";
        }
        if (f.indexOf(',') < 0 && f.indexOf('"') < 0) {
            return f;
        }
        return '"' + f.replace("\"", "\"\"") + '"';
    }

    // -------------------------------------------------------------------
    // Threshold/cap CLI overrides.
    // -------------------------------------------------------------------
    private static void applyOverrides(Config.Configuration synthetic,
            Args args) {
        if (args.strongThreshold != null) {
            synthetic.traceStrongScoreThreshold = args.strongThreshold;
        }
        if (args.weakThreshold != null) {
            synthetic.traceWeakScoreThreshold = args.weakThreshold;
        }
        if (args.minBaselineAgreement != null) {
            synthetic.traceMinBaselineAgreementForStrong = args.minBaselineAgreement;
        }
        if (args.minRollingDivergence != null) {
            synthetic.traceMinRollingDivergenceForStrong = args.minRollingDivergence;
        }
        if (args.backgroundCap != null) {
            synthetic.traceBackgroundCap = args.backgroundCap;
        }
        if (args.boundaryBonus != null) {
            synthetic.traceBoundaryBonus = args.boundaryBonus;
        }
        if (args.orderBonusCap != null) {
            synthetic.traceOrderBonusCap = args.orderBonusCap;
        }
    }

    // -------------------------------------------------------------------
    // POJOs.
    // -------------------------------------------------------------------

    /** Replay tier the CSV belongs to. */
    public enum Tier {
        AUTO, HISTORICAL, POST_INSTRUMENTATION
    }

    /** Parsed CLI args (public so unit tests can build them by hand). */
    public static final class Args {
        public List<Path> inputs = new ArrayList<>();
        public TraceSystemPreset preset = TraceSystemPreset.AUTO;
        public String system;
        public Tier tier = Tier.AUTO;
        public Path outDir;
        public String hostOverride;
        public Path familyMapProfile;
        public boolean help;
        public Double strongThreshold;
        public Double weakThreshold;
        public Double minBaselineAgreement;
        public Double minRollingDivergence;
        public Double backgroundCap;
        public Double boundaryBonus;
        public Double orderBonusCap;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String arg = argv[i];
                switch (arg) {
                case "-h":
                case "--help":
                    a.help = true;
                    break;
                case "--input-dir":
                    a.inputs.add(Paths.get(requireValue(argv, ++i, arg)));
                    break;
                case "--preset":
                    a.preset = TraceSystemPreset
                            .valueOf(requireValue(argv, ++i, arg)
                                    .toUpperCase(Locale.ROOT));
                    break;
                case "--system":
                    a.system = requireValue(argv, ++i, arg);
                    break;
                case "--tier":
                    a.tier = parseTier(requireValue(argv, ++i, arg));
                    break;
                case "--out-dir":
                    a.outDir = Paths.get(requireValue(argv, ++i, arg));
                    break;
                case "--host":
                    a.hostOverride = requireValue(argv, ++i, arg);
                    break;
                case "--family-map-profile":
                    a.familyMapProfile = Paths
                            .get(requireValue(argv, ++i, arg));
                    break;
                case "--strong-threshold":
                    a.strongThreshold = Double
                            .parseDouble(requireValue(argv, ++i, arg));
                    break;
                case "--weak-threshold":
                    a.weakThreshold = Double
                            .parseDouble(requireValue(argv, ++i, arg));
                    break;
                case "--min-baseline-agreement":
                    a.minBaselineAgreement = Double
                            .parseDouble(requireValue(argv, ++i, arg));
                    break;
                case "--min-rolling-divergence":
                    a.minRollingDivergence = Double
                            .parseDouble(requireValue(argv, ++i, arg));
                    break;
                case "--background-cap":
                    a.backgroundCap = Double
                            .parseDouble(requireValue(argv, ++i, arg));
                    break;
                case "--boundary-bonus":
                    a.boundaryBonus = Double
                            .parseDouble(requireValue(argv, ++i, arg));
                    break;
                case "--order-bonus-cap":
                    a.orderBonusCap = Double
                            .parseDouble(requireValue(argv, ++i, arg));
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Unknown argument: " + arg);
                }
            }
            if (!a.help && a.inputs.isEmpty()) {
                throw new IllegalArgumentException("--input-dir is required");
            }
            return a;
        }

        private static Tier parseTier(String s) {
            switch (s.toLowerCase(Locale.ROOT)) {
            case "auto":
                return Tier.AUTO;
            case "historical":
            case "historical-artifact":
                return Tier.HISTORICAL;
            case "post-instrumentation":
            case "post":
                return Tier.POST_INSTRUMENTATION;
            default:
                throw new IllegalArgumentException("Unknown tier: " + s);
            }
        }

        private static String requireValue(String[] argv, int i, String flag) {
            if (i >= argv.length) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            return argv[i];
        }
    }

    /** Per-row parsed view (subset of WindowTriggerRow we need for replay). */
    static final class Row {
        long round;
        int testPacketId;
        int windowOrdinal;
        String comparisonStage;
        boolean windowSimFired;
        boolean triDiffExclusiveFired;
        boolean triDiffMissingFired;
        /** True iff any of the three "fired" columns is true. */
        boolean windowFired;
        boolean supportGatePassed;
        int upgradedBoundaryEventCount;
        int changedMessageCount;
        TraceEvidenceStrength originalStrength = TraceEvidenceStrength.NONE;
        String supportClass;
        double compositeScore;
        double baselineAgreementScore;
        double rollingDivergenceScore;
        String dominantSupportedFamily;
        String dominantDivergentFamily;
        String dominantOrderAnomalousFamily;
        /** Phase 2 column carrying per-family detail labels for the rolling lane. */
        String flowTopDivergentDetailsRolling;
        boolean rollingOnlyUpgradeCriticalPresent;
    }

    /** Per-host aggregate counters. */
    static final class HostAggregate {
        Tier detectedTier;
        boolean tierMismatch;
        int totalRows;
        Map<TraceEvidenceStrength, Integer> originalCounts = new java.util.EnumMap<>(
                TraceEvidenceStrength.class);
        Map<TraceEvidenceStrength, Integer> replayCounts = new java.util.EnumMap<>(
                TraceEvidenceStrength.class);
        Map<String, Integer> transitionCounter = new TreeMap<>();
        Map<String, Integer> topSupportedFamilies = new HashMap<>();
        Map<String, Integer> topDivergentFamilies = new HashMap<>();
        Map<String, Integer> topOrderAnomalousFamilies = new HashMap<>();
        /**
         * Frequency of (family, detail-label) tuples mined from the
         * Phase-2 {@code flow_top_divergent_details_rolling} column. Keys
         * are formatted as {@code FAMILY|label} so the per-family
         * service / method / verb tail is preserved in the aggregate.
         */
        Map<String, Long> serviceMethodLabelCounts = new HashMap<>();
        /** Per-(round, test_packet_id, window_ordinal) row buffer for per-window CSV. */
        List<PerWindowRecord> perWindow = new ArrayList<>();
        MetadataCoverage metadataCoverage;
        /**
         * Per-tuple re-classification stats from
         * trace_window_classifier_inputs.csv when present. Null when the
         * sidecar was not emitted by the live run.
         */
        ClassifierTupleReplay classifierTupleStats;
        /**
         * Whether trace_window_classifier_inputs.csv was present on the
         * source observability directory. Distinguishes "dump was off
         * during the live run" ({@code false}) from "dump was on but
         * had zero tuples" ({@code true} + {@code classifierTupleStats.totalRows=0}).
         */
        boolean classifierSidecarPresent;

        void recordOriginal(TraceEvidenceStrength s) {
            originalCounts.merge(s, 1, Integer::sum);
        }

        void recordReplay(TraceEvidenceStrength s) {
            replayCounts.merge(s, 1, Integer::sum);
        }
    }

    /**
     * Per-window record emitted into {@code replay_per_window.csv}. Carries
     * the original strength, the replay strength, the recommended queue
     * routing, and the support / score components when present.
     */
    static final class PerWindowRecord {
        final String host;
        final long round;
        final int testPacketId;
        final int windowOrdinal;
        final String comparisonStage;
        final TraceEvidenceStrength originalStrength;
        final TraceEvidenceStrength replayStrength;
        final String supportClass;
        final double compositeScore;
        final double baselineAgreementScore;
        final double rollingDivergenceScore;
        final boolean windowSimFired;
        final boolean triDiffExclusiveFired;
        final boolean triDiffMissingFired;
        final int upgradedBoundaryEventCount;
        final int changedMessageCount;
        final String dominantSupportedFamily;
        final String dominantDivergentFamily;
        final String dominantOrderAnomalousFamily;
        final String recommendedRouting;

        PerWindowRecord(String host, Row r,
                TraceEvidenceStrength replayStrength,
                String recommendedRouting) {
            this.host = host;
            this.round = r.round;
            this.testPacketId = r.testPacketId;
            this.windowOrdinal = r.windowOrdinal;
            this.comparisonStage = r.comparisonStage;
            this.originalStrength = r.originalStrength;
            this.replayStrength = replayStrength;
            this.supportClass = r.supportClass;
            this.compositeScore = r.compositeScore;
            this.baselineAgreementScore = r.baselineAgreementScore;
            this.rollingDivergenceScore = r.rollingDivergenceScore;
            this.windowSimFired = r.windowSimFired;
            this.triDiffExclusiveFired = r.triDiffExclusiveFired;
            this.triDiffMissingFired = r.triDiffMissingFired;
            this.upgradedBoundaryEventCount = r.upgradedBoundaryEventCount;
            this.changedMessageCount = r.changedMessageCount;
            this.dominantSupportedFamily = r.dominantSupportedFamily;
            this.dominantDivergentFamily = r.dominantDivergentFamily;
            this.dominantOrderAnomalousFamily = r.dominantOrderAnomalousFamily;
            this.recommendedRouting = recommendedRouting;
        }
    }

    /** Aggregate metadata-coverage stats from trace_metadata_coverage.csv. */
    static final class MetadataCoverage {
        long rows;
        long rpcServicePresent;
        long rpcMethodPresent;
        long messageKindPresent;
        long logicalMessageIdPresent;
        long deliveryIdPresent;
    }

    /**
     * Phase 5 Tier-2 per-host re-classification stats. Records every
     * {@code (rpcService, rpcMethod, messageType, payloadType,
     * messageKind, protocol, oldFamily, newFamily)} change observed
     * when the loaded family-map profile is applied to the
     * {@code trace_window_classifier_inputs.csv} sidecar.
     */
    static final class ClassifierTupleReplay {
        long totalRows;
        long rowsRetagged;
        /** Family transitions: key = "OLD->NEW", value = aggregated event count. */
        Map<String, Long> familyTransitions = new TreeMap<>();
        /** Per-(family-transition, label) counters for forensic drilldown. */
        Map<String, Long> tupleTransitions = new TreeMap<>();
    }

    /** Top-level replay output, keyed by host. */
    static final class ReplayResult {
        final Map<String, HostAggregate> hosts = new LinkedHashMap<>();

        HostAggregate hostFor(String host) {
            return hosts.computeIfAbsent(host, h -> new HostAggregate());
        }

        ReplayTotals totals() {
            ReplayTotals t = new ReplayTotals();
            for (HostAggregate ha : hosts.values()) {
                t.totalRows += ha.totalRows;
                ha.originalCounts.forEach(
                        (k, v) -> t.originalCounts.merge(k, v, Integer::sum));
                ha.replayCounts.forEach(
                        (k, v) -> t.replayCounts.merge(k, v, Integer::sum));
                ha.topSupportedFamilies.forEach((k, v) -> t.topSupportedFamilies
                        .merge(k, v, Integer::sum));
                ha.topDivergentFamilies.forEach((k, v) -> t.topDivergentFamilies
                        .merge(k, v, Integer::sum));
                ha.topOrderAnomalousFamilies
                        .forEach((k, v) -> t.topOrderAnomalousFamilies.merge(k,
                                v, Integer::sum));
            }
            return t;
        }
    }

    static final class ReplayTotals {
        int totalRows;
        Map<TraceEvidenceStrength, Integer> originalCounts = new java.util.EnumMap<>(
                TraceEvidenceStrength.class);
        Map<TraceEvidenceStrength, Integer> replayCounts = new java.util.EnumMap<>(
                TraceEvidenceStrength.class);
        Map<String, Integer> topSupportedFamilies = new HashMap<>();
        Map<String, Integer> topDivergentFamilies = new HashMap<>();
        Map<String, Integer> topOrderAnomalousFamilies = new HashMap<>();
    }
}
