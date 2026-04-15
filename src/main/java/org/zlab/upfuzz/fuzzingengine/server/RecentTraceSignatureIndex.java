package org.zlab.upfuzz.fuzzingengine.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * Bounded recent-signature index for Phase 4 trace dedup.
 *
 * <p>The index tracks compact per-window signatures for recently admitted
 * trace-only seeds. A later trace-only admission is suppressed when every
 * signature it carries is already saturated in the lookback window. Branch-
 * backed admissions are handled above this class and never query it.
 *
 * <p>Memory is bounded in two ways:
 * <ul>
 * <li>a sliding round lookback forgets old entries;</li>
 * <li>a hard entry cap evicts the oldest signatures first.</li>
 * </ul>
 *
 * <p>The index records at most one instance of a given signature per admitted
 * seed, even if multiple windows in that seed collapse to the same signature.
 */
final class RecentTraceSignatureIndex {
    private final int saturationThreshold;
    private final int lookbackRounds;
    private final int capacity;

    private final Deque<RecordedSignature> recent = new ArrayDeque<>();
    private final Map<TraceSignature, Integer> liveCounts = new HashMap<>();

    private long totalRecorded = 0L;
    private long totalSuppressedDuplicates = 0L;
    private long totalLookbackEvictions = 0L;
    private long totalCapacityEvictions = 0L;

    RecentTraceSignatureIndex(
            int saturationThreshold,
            int lookbackRounds,
            int capacity) {
        this.saturationThreshold = Math.max(1, saturationThreshold);
        this.lookbackRounds = Math.max(1, lookbackRounds);
        this.capacity = Math.max(1, capacity);
    }

    public synchronized boolean shouldSuppress(List<TraceSignature> signatures,
            long round) {
        prune(round);
        Set<TraceSignature> unique = unique(signatures);
        if (unique.isEmpty()) {
            return false;
        }
        for (TraceSignature signature : unique) {
            if (liveCounts.getOrDefault(signature, 0) < saturationThreshold) {
                return false;
            }
        }
        totalSuppressedDuplicates++;
        return true;
    }

    public synchronized void recordAdmitted(List<TraceSignature> signatures,
            long round) {
        prune(round);
        Set<TraceSignature> unique = unique(signatures);
        if (unique.isEmpty()) {
            return;
        }
        for (TraceSignature signature : unique) {
            recent.addLast(new RecordedSignature(round, signature));
            liveCounts.put(signature,
                    liveCounts.getOrDefault(signature, 0) + 1);
            totalRecorded++;
        }
        enforceCapacity();
    }

    public synchronized int liveSignatureCount() {
        return liveCounts.size();
    }

    public synchronized int recentEntryCount() {
        return recent.size();
    }

    public synchronized long getTotalRecorded() {
        return totalRecorded;
    }

    public synchronized long getTotalSuppressedDuplicates() {
        return totalSuppressedDuplicates;
    }

    public synchronized long getTotalLookbackEvictions() {
        return totalLookbackEvictions;
    }

    public synchronized long getTotalCapacityEvictions() {
        return totalCapacityEvictions;
    }

    synchronized int getLiveCountForTest(TraceSignature signature, long round) {
        prune(round);
        return liveCounts.getOrDefault(signature, 0);
    }

    private void prune(long round) {
        while (!recent.isEmpty()) {
            RecordedSignature oldest = recent.peekFirst();
            if (round - oldest.round < lookbackRounds) {
                break;
            }
            recent.pollFirst();
            decrementLiveCount(oldest.signature);
            totalLookbackEvictions++;
        }
    }

    private void enforceCapacity() {
        while (recent.size() > capacity) {
            RecordedSignature oldest = recent.pollFirst();
            if (oldest == null) {
                return;
            }
            decrementLiveCount(oldest.signature);
            totalCapacityEvictions++;
        }
    }

    private void decrementLiveCount(TraceSignature signature) {
        Integer current = liveCounts.get(signature);
        if (current == null) {
            return;
        }
        if (current <= 1) {
            liveCounts.remove(signature);
        } else {
            liveCounts.put(signature, current - 1);
        }
    }

    private static Set<TraceSignature> unique(List<TraceSignature> signatures) {
        LinkedHashSet<TraceSignature> unique = new LinkedHashSet<>();
        if (signatures == null) {
            return unique;
        }
        for (TraceSignature signature : signatures) {
            if (signature != null) {
                unique.add(signature);
            }
        }
        return unique;
    }

    private static final class RecordedSignature {
        final long round;
        final TraceSignature signature;

        RecordedSignature(long round, TraceSignature signature) {
            this.round = round;
            this.signature = signature;
        }
    }
}
