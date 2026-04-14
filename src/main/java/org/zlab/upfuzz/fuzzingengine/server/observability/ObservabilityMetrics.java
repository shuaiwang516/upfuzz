package org.zlab.upfuzz.fuzzingengine.server.observability;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Aggregates Phase 0 measurement data and writes run artifacts.
 *
 * <p>Lives as a single instance on the fuzzing server. Updates happen on the
 * server thread (under {@code FuzzingServer}'s monitor), but data structures
 * are concurrent so offline readers (e.g. status threads) can sample safely.
 *
 * <p>Four CSVs are produced under {@code <failureDir>/observability/}:
 * <ul>
 *   <li>{@code trace_admission_summary.csv} — one row per completed
 *       differential execution, with the rule-fired flags, the primary
 *       admission reason, and cumulative counts after the row. This is the
 *       per-round artifact the Phase 0 plan calls for.</li>
 *   <li>{@code trace_admission_totals.csv} — a small aggregate
 *       {@code reason,count} snapshot, refreshed every flush. Redundant
 *       with the per-round CSV but cheap and useful for quick eyeballing
 *       in the status line.</li>
 *   <li>{@code seed_lifecycle_summary.csv} — one row per saved seed, with
 *       parent-selection counts and downstream branch / structured
 *       candidate / weak candidate payoff counters.</li>
 *   <li>{@code trace_window_summary.csv} — one row per window evaluated
 *       by the canonical-trace scoring pass, regardless of whether it
 *       fired.</li>
 * </ul>
 *
 * <p>Writes are atomic: CSVs are staged in {@code .tmp} files and renamed in
 * place so a concurrent reader or a campaign kill never sees a truncated
 * file.
 */
public final class ObservabilityMetrics {
    private static final Logger logger = LogManager
            .getLogger(ObservabilityMetrics.class);

    public static final int NO_PARENT = -1;
    public static final int UNKNOWN_PACKET = -1;

    private static final String ADMISSION_CSV_NAME = "trace_admission_summary.csv";
    private static final String ADMISSION_TOTALS_CSV_NAME = "trace_admission_totals.csv";
    private static final String LIFECYCLE_CSV_NAME = "seed_lifecycle_summary.csv";
    private static final String WINDOW_CSV_NAME = "trace_window_summary.csv";

    private final EnumMap<AdmissionReason, AtomicLong> admissionCounts = new EnumMap<>(
            AdmissionReason.class);

    private final Map<Integer, SeedLifecycle> seedLifecycles = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> childToParent = new ConcurrentHashMap<>();

    private final List<WindowTriggerRow> windowRows = new ArrayList<>();
    private final List<AdmissionSummaryRow> admissionRows = new ArrayList<>();

    private volatile boolean enabled = true;
    private final Path outputDir;
    private final Object writeLock = new Object();

    public ObservabilityMetrics(Path outputDir) {
        this.outputDir = outputDir;
        for (AdmissionReason reason : AdmissionReason.values()) {
            admissionCounts.put(reason, new AtomicLong(0));
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    // === Admission counters ===

    public void recordAdmission(AdmissionReason reason) {
        if (!enabled) {
            return;
        }
        AdmissionReason resolved = reason == null ? AdmissionReason.UNKNOWN
                : reason;
        admissionCounts.get(resolved).incrementAndGet();
    }

    public long getAdmissionCount(AdmissionReason reason) {
        AtomicLong c = admissionCounts.get(reason);
        return c == null ? 0L : c.get();
    }

    // === Per-round summary rows ===

    public void recordAdmissionSummary(AdmissionSummaryRow row) {
        if (!enabled || row == null) {
            return;
        }
        synchronized (admissionRows) {
            admissionRows.add(row);
        }
    }

    public int admissionRowCount() {
        synchronized (admissionRows) {
            return admissionRows.size();
        }
    }

    // === Per-window rows ===

    public void recordWindowTrigger(WindowTriggerRow row) {
        if (!enabled || row == null) {
            return;
        }
        synchronized (windowRows) {
            windowRows.add(row);
        }
    }

    public int windowRowCount() {
        synchronized (windowRows) {
            return windowRows.size();
        }
    }

    // === Seed lifecycle ===

    /**
     * Register a lifecycle record for a newly admitted seed. The first
     * registration for a given {@code seedTestId} wins — re-registration is
     * a no-op to preserve the original creation reason.
     */
    public SeedLifecycle recordSeedAddition(
            int seedTestId,
            long creationRound,
            AdmissionReason creationReason,
            int parentSeedTestId) {
        if (!enabled || seedTestId < 0) {
            return null;
        }
        SeedLifecycle record = new SeedLifecycle(
                seedTestId,
                creationRound,
                System.currentTimeMillis(),
                creationReason,
                parentSeedTestId);
        SeedLifecycle existing = seedLifecycles.putIfAbsent(seedTestId,
                record);
        return existing == null ? record : existing;
    }

    public SeedLifecycle getLifecycle(int seedTestId) {
        return seedLifecycles.get(seedTestId);
    }

    public int lifecycleCount() {
        return seedLifecycles.size();
    }

    // === Lineage tracking ===

    public void linkChildToParent(int childTestId, int parentTestId) {
        if (!enabled || childTestId < 0 || parentTestId < 0) {
            return;
        }
        childToParent.put(childTestId, parentTestId);
    }

    public int resolveRootLifecycleId(int testId) {
        int cursor = testId;
        for (int hops = 0; hops < 1024 && cursor >= 0; hops++) {
            if (seedLifecycles.containsKey(cursor)) {
                return cursor;
            }
            Integer parent = childToParent.get(cursor);
            if (parent == null) {
                return NO_PARENT;
            }
            cursor = parent;
        }
        return NO_PARENT;
    }

    public void recordParentSelection(int seedTestId) {
        if (!enabled || seedTestId < 0) {
            return;
        }
        SeedLifecycle record = seedLifecycles.get(seedTestId);
        if (record != null) {
            record.timesSelectedAsParent.incrementAndGet();
        }
    }

    public void recordDownstreamBranchHit(int childTestId) {
        if (!enabled || childTestId < 0) {
            return;
        }
        int rootId = resolveRootLifecycleId(childTestId);
        if (rootId == NO_PARENT) {
            return;
        }
        SeedLifecycle record = seedLifecycles.get(rootId);
        if (record != null) {
            record.descendantNewBranchHits.incrementAndGet();
        }
    }

    /**
     * Credit the parent for a structured cross-cluster divergence (Checker
     * D). This is the only candidate category that Phase 2 retention
     * policy should rely on — everything else is currently too noisy.
     */
    public void recordDownstreamStructuredCandidateHit(int childTestId) {
        if (!enabled || childTestId < 0) {
            return;
        }
        int rootId = resolveRootLifecycleId(childTestId);
        if (rootId == NO_PARENT) {
            return;
        }
        SeedLifecycle record = seedLifecycles.get(rootId);
        if (record != null) {
            record.descendantStructuredCandidateHits.incrementAndGet();
        }
    }

    /**
     * Credit the parent for a weak rolling-upgrade candidate (event-crash
     * rolling-only, error-log rolling-only). Tracked separately so that
     * Phase 5 candidate-routing cleanup is measurable without corrupting
     * the structured signal.
     */
    public void recordDownstreamWeakCandidateHit(int childTestId) {
        if (!enabled || childTestId < 0) {
            return;
        }
        int rootId = resolveRootLifecycleId(childTestId);
        if (rootId == NO_PARENT) {
            return;
        }
        SeedLifecycle record = seedLifecycles.get(rootId);
        if (record != null) {
            record.descendantWeakCandidateHits.incrementAndGet();
        }
    }

    // === Artifact writers ===

    public void writeAllArtifacts() {
        if (!enabled) {
            return;
        }
        if (outputDir == null) {
            return;
        }
        synchronized (writeLock) {
            try {
                Files.createDirectories(outputDir);
                writeAdmissionSummaryCsv();
                writeAdmissionTotalsCsv();
                writeLifecycleCsv();
                writeWindowCsv();
            } catch (IOException e) {
                logger.warn("Failed to write observability artifacts", e);
            }
        }
    }

    private void writeAdmissionSummaryCsv() throws IOException {
        Path target = outputDir.resolve(ADMISSION_CSV_NAME);
        Path tmp = outputDir.resolve(ADMISSION_CSV_NAME + ".tmp");
        List<AdmissionSummaryRow> snapshot;
        synchronized (admissionRows) {
            snapshot = new ArrayList<>(admissionRows);
        }
        try (BufferedWriter w = Files.newBufferedWriter(tmp,
                StandardCharsets.UTF_8)) {
            w.write(AdmissionSummaryRow.csvHeader());
            w.newLine();
            for (AdmissionSummaryRow row : snapshot) {
                w.write(row.toCsvRow());
                w.newLine();
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    private void writeAdmissionTotalsCsv() throws IOException {
        Path target = outputDir.resolve(ADMISSION_TOTALS_CSV_NAME);
        Path tmp = outputDir.resolve(ADMISSION_TOTALS_CSV_NAME + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp,
                StandardCharsets.UTF_8)) {
            w.write("reason,count");
            w.newLine();
            for (AdmissionReason reason : AdmissionReason.values()) {
                long count = admissionCounts.get(reason).get();
                w.write(reason.name());
                w.write(',');
                w.write(Long.toString(count));
                w.newLine();
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    private void writeLifecycleCsv() throws IOException {
        Path target = outputDir.resolve(LIFECYCLE_CSV_NAME);
        Path tmp = outputDir.resolve(LIFECYCLE_CSV_NAME + ".tmp");
        List<SeedLifecycle> snapshot = new ArrayList<>(seedLifecycles.values());
        snapshot.sort((a, b) -> {
            int byRound = Long.compare(a.creationRound, b.creationRound);
            if (byRound != 0) {
                return byRound;
            }
            return Integer.compare(a.seedTestId, b.seedTestId);
        });
        try (BufferedWriter w = Files.newBufferedWriter(tmp,
                StandardCharsets.UTF_8)) {
            w.write(SeedLifecycle.csvHeader());
            w.newLine();
            for (SeedLifecycle row : snapshot) {
                w.write(row.toCsvRow());
                w.newLine();
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    private void writeWindowCsv() throws IOException {
        Path target = outputDir.resolve(WINDOW_CSV_NAME);
        Path tmp = outputDir.resolve(WINDOW_CSV_NAME + ".tmp");
        List<WindowTriggerRow> snapshot;
        synchronized (windowRows) {
            snapshot = new ArrayList<>(windowRows);
        }
        try (BufferedWriter w = Files.newBufferedWriter(tmp,
                StandardCharsets.UTF_8)) {
            w.write(WindowTriggerRow.csvHeader());
            w.newLine();
            for (WindowTriggerRow row : snapshot) {
                w.write(row.toCsvRow());
                w.newLine();
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
