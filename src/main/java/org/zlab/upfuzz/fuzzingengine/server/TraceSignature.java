package org.zlab.upfuzz.fuzzingengine.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.zlab.net.tracker.CanonicalKeyMode;
import org.zlab.upfuzz.fuzzingengine.trace.TraceWindow;

/**
 * Compact, repeatable identity for one interesting trace window.
 *
 * <p>Phase 4 uses these signatures to suppress trace-only admissions that keep
 * rediscovering the same network-divergence shape. The signature is coarse on
 * purpose: it keeps only the stage key, canonical-key tier, the top exclusive
 * and missing bucket identities, and an optional similarity bucket for
 * window-similarity hits. The exact bucket counts are intentionally dropped so
 * benign count drift does not defeat dedup.
 */
final class TraceSignature {
    static final String NO_SIMILARITY_BUCKET = "-";
    private static final double SIMILARITY_BUCKET_WIDTH = 0.05;

    final String stageKey;
    final CanonicalKeyMode canonicalKeyMode;
    final List<String> topExclusiveBuckets;
    final List<String> topMissingBuckets;
    final String similarityBucket;

    TraceSignature(
            String stageKey,
            CanonicalKeyMode canonicalKeyMode,
            List<String> topExclusiveBuckets,
            List<String> topMissingBuckets,
            String similarityBucket) {
        this.stageKey = stageKey == null ? "unknown|nodes=[]" : stageKey;
        this.canonicalKeyMode = canonicalKeyMode == null
                ? CanonicalKeyMode.SEMANTIC
                : canonicalKeyMode;
        this.topExclusiveBuckets = immutableCopy(topExclusiveBuckets);
        this.topMissingBuckets = immutableCopy(topMissingBuckets);
        this.similarityBucket = similarityBucket == null
                || similarityBucket.isEmpty()
                        ? NO_SIMILARITY_BUCKET
                        : similarityBucket;
    }

    static TraceSignature fromWindow(
            TraceWindow window,
            CanonicalKeyMode canonicalKeyMode,
            Map<String, Integer> rollingExclusiveBuckets,
            Map<String, Integer> rollingMissingBuckets,
            boolean windowSimilarityInteresting,
            double rollingMinSimilarity,
            double rollingDivergenceMargin,
            int topBucketLimit) {
        return new TraceSignature(
                stageKeyOf(window),
                canonicalKeyMode,
                selectTopBucketKeys(rollingExclusiveBuckets, topBucketLimit),
                selectTopBucketKeys(rollingMissingBuckets, topBucketLimit),
                windowSimilarityInteresting
                        ? similarityBucketOf(rollingMinSimilarity,
                                rollingDivergenceMargin)
                        : NO_SIMILARITY_BUCKET);
    }

    private static List<String> immutableCopy(List<String> buckets) {
        if (buckets == null || buckets.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(buckets));
    }

    private static String stageKeyOf(TraceWindow window) {
        if (window == null) {
            return "unknown|nodes=[]";
        }
        return window.comparisonStageId + "|nodes="
                + normalizedNodeSet(window.normalizedTransitionNodeSet);
    }

    private static String normalizedNodeSet(Set<Integer> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "[]";
        }
        List<Integer> sorted = new ArrayList<>(nodes);
        Collections.sort(sorted);
        return sorted.toString();
    }

    private static List<String> selectTopBucketKeys(
            Map<String, Integer> buckets,
            int topBucketLimit) {
        if (buckets == null || buckets.isEmpty() || topBucketLimit <= 0) {
            return Collections.emptyList();
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(
                buckets.entrySet());
        entries.sort(Comparator
                .comparing(Map.Entry<String, Integer>::getValue,
                        Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey));

        int limit = Math.min(entries.size(), topBucketLimit);
        List<String> selected = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            selected.add(entries.get(i).getKey());
        }
        Collections.sort(selected);
        return selected;
    }

    private static String similarityBucketOf(
            double rollingMinSimilarity,
            double rollingDivergenceMargin) {
        double simBucket = floorBucket(clamp01(rollingMinSimilarity),
                SIMILARITY_BUCKET_WIDTH);
        double marginBucket = floorBucket(Math.max(0.0,
                rollingDivergenceMargin), SIMILARITY_BUCKET_WIDTH);
        return String.format(Locale.ROOT, "sim=%.2f|margin=%.2f",
                simBucket, marginBucket);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private static double floorBucket(double value, double width) {
        if (width <= 0.0) {
            return value;
        }
        return Math.floor(value / width) * width;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TraceSignature)) {
            return false;
        }
        TraceSignature that = (TraceSignature) other;
        return Objects.equals(this.stageKey, that.stageKey)
                && this.canonicalKeyMode == that.canonicalKeyMode
                && Objects.equals(this.topExclusiveBuckets,
                        that.topExclusiveBuckets)
                && Objects.equals(this.topMissingBuckets,
                        that.topMissingBuckets)
                && Objects.equals(this.similarityBucket,
                        that.similarityBucket);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stageKey, canonicalKeyMode, topExclusiveBuckets,
                topMissingBuckets, similarityBucket);
    }

    @Override
    public String toString() {
        return "TraceSignature{stage=" + stageKey
                + ", keyMode=" + canonicalKeyMode
                + ", excl=" + topExclusiveBuckets
                + ", miss=" + topMissingBuckets
                + ", sim=" + similarityBucket + "}";
    }
}
