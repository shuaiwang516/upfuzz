package org.zlab.upfuzz.fuzzingengine.server;

/**
 * Phase 3 composite support model for an aligned trace window.
 *
 * <p>The Apr16 campaign showed that detailed all-three message-bucket
 * overlap is too strict for rolling-upgrade executions: two lanes can
 * express the same logical work through slightly different message
 * shapes and still never clear the old "all3 &gt; 0" gate. Phase 3
 * redefines support as a ladder of progressively stronger overlap
 * notions so the scorer can explain <em>why</em> a window is supported
 * and tune gating behavior per tier.
 *
 * <p>Tiers (weakest first):
 * <ul>
 *   <li>{@link #UNSUPPORTED} — no 3-way overlap at any tier. The
 *       baselines may share traffic the rolling lane never produced,
 *       but there is no common reference point across all three lanes.</li>
 *   <li>{@link #BACKGROUND_ONLY} — 3-way overlap exists but only on
 *       {@link org.zlab.net.tracker.classifier.ProtocolFamilyClass#BACKGROUND}
 *       families (gossip / heartbeats / status reports). Visible but
 *       never sufficient for
 *       {@link org.zlab.upfuzz.fuzzingengine.server.observability.TraceEvidenceStrength#STRONG}
 *       on its own.</li>
 *   <li>{@link #FAMILY_BACKED} — 3-way family overlap exists on at least
 *       one non-background family. The lanes agree on the <em>kind</em>
 *       of traffic happening, but the extractor could not correlate
 *       specific logical exchanges across lanes.</li>
 *   <li>{@link #FLOW_BACKED} — 3-way flow overlap exists (including
 *       deterministic-fallback buckets). Stronger than
 *       {@link #FAMILY_BACKED} because the lanes share at least one
 *       recurring exchange shape within the same role pair.</li>
 *   <li>{@link #FULL} — 3-way flow overlap exists on at least one
 *       explicit-ID flow (logical message id or delivery id). The lanes
 *       agree at request-level identity — the strongest support tier.</li>
 * </ul>
 */
public enum TraceSupportClass {
    UNSUPPORTED(0), BACKGROUND_ONLY(1), FAMILY_BACKED(2), FLOW_BACKED(3), FULL(
            4);

    private final int rank;

    TraceSupportClass(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean atLeast(TraceSupportClass other) {
        return other != null && this.rank >= other.rank;
    }
}
