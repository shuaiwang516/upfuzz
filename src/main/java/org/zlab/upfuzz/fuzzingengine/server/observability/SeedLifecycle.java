package org.zlab.upfuzz.fuzzingengine.server.observability;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-seed lifecycle metadata for a corpus-admitted seed.
 *
 * <p>Created when a seed is admitted to the corpus. Counters are mutated
 * later as the seed is selected as a mutation parent and as its descendants
 * produce downstream branch hits or candidate artifacts.
 *
 * <p>Phase 0 used this record to answer two questions after a run:
 * <ul>
 *   <li>What rule admitted this seed?</li>
 *   <li>Did that seed later pay off in new branch coverage or structured
 *       tri-lane candidates?</li>
 * </ul>
 *
 * <p>Phase 1 splits the Phase 0 {@code descendantStructuredCandidateHits}
 * bucket into {@code descendantStrongStructuredCandidateHits} (strong,
 * confident checker-D divergences) and keeps
 * {@code descendantStructuredCandidateHits} as a backward-compatible
 * alias for the strong count — only strong structured candidates are
 * allowed to promote parent seeds. Weak structured divergences, rolling-
 * only event crashes, and rolling-only error logs each get their own
 * counter under the weak umbrella so offline analysis can see which
 * kind of noise dominates a given campaign.
 */
public final class SeedLifecycle {
    public final int seedTestId;
    public final long creationRound;
    public final long creationTimestampMs;
    public final AdmissionReason creationReason;
    public final int parentSeedTestId;

    public final AtomicLong timesSelectedAsParent = new AtomicLong(0);
    public final AtomicLong descendantNewBranchHits = new AtomicLong(0);

    /**
     * Strong structured candidates (Phase 1). Before Phase 1 this field
     * counted all structured candidates — strong and weak combined.
     * Phase 1 restricts it to the strong slice because only strong
     * structured candidates promote parent seeds, so existing parsers
     * of {@code seed_lifecycle_summary.csv} that only look at this
     * column now see a cleaner signal.
     */
    public final AtomicLong descendantStructuredCandidateHits = new AtomicLong(
            0);

    /** Phase 1: weak structured divergences, tracked separately. */
    public final AtomicLong descendantWeakStructuredCandidateHits = new AtomicLong(
            0);
    /** Phase 1: rolling-only event-crash candidates. */
    public final AtomicLong descendantWeakEventCandidateHits = new AtomicLong(
            0);
    /** Phase 1: rolling-only error-log candidates. */
    public final AtomicLong descendantWeakErrorLogCandidateHits = new AtomicLong(
            0);

    /**
     * Phase 5: downstream rounds that produced STRONG trace evidence.
     * This is a distinct signal from structured candidates — it tracks
     * whether a seed's descendants triggered strong mixed-version trace
     * patterns, which correlates with upgrade-relevant behavioral
     * divergence even when no structured checker-D candidate fires.
     */
    public final AtomicLong descendantStrongTraceHits = new AtomicLong(0);

    /**
     * Phase 5: branch novelty class at creation time. Records whether
     * this seed was admitted with rolling-post-upgrade novelty,
     * rolling-pre-upgrade-only novelty, shared novelty, baseline-only
     * novelty, or no branch novelty (trace-only admission). Offline
     * analysis uses this to correlate novelty quality with downstream
     * payoff — "seeds admitted with ROLLING_POST_UPGRADE produced N
     * strong candidates".
     */
    public final BranchNoveltyClass branchNoveltyClass;

    /**
     * Weak candidate umbrella preserving Phase 0 semantics: sum of
     * rolling-only event crash + rolling-only error log hits. Weak
     * <em>structured</em> divergence is NOT rolled into this counter
     * because offline parsers of {@code seed_lifecycle_summary.csv}
     * expected the Phase 0 "rolling-only weak signal" meaning. To
     * see the full Phase 1 weak picture, offline code should sum
     * {@link #descendantWeakStructuredCandidateHits},
     * {@link #descendantWeakEventCandidateHits}, and
     * {@link #descendantWeakErrorLogCandidateHits}.
     */
    public final AtomicLong descendantWeakCandidateHits = new AtomicLong(0);

    public SeedLifecycle(
            int seedTestId,
            long creationRound,
            long creationTimestampMs,
            AdmissionReason creationReason,
            int parentSeedTestId) {
        this(seedTestId, creationRound, creationTimestampMs, creationReason,
                parentSeedTestId, BranchNoveltyClass.NONE);
    }

    public SeedLifecycle(
            int seedTestId,
            long creationRound,
            long creationTimestampMs,
            AdmissionReason creationReason,
            int parentSeedTestId,
            BranchNoveltyClass branchNoveltyClass) {
        this.seedTestId = seedTestId;
        this.creationRound = creationRound;
        this.creationTimestampMs = creationTimestampMs;
        this.creationReason = creationReason == null
                ? AdmissionReason.UNKNOWN
                : creationReason;
        this.parentSeedTestId = parentSeedTestId;
        this.branchNoveltyClass = branchNoveltyClass == null
                ? BranchNoveltyClass.NONE
                : branchNoveltyClass;
    }

    public static String csvHeader() {
        return String.join(",",
                "seed_test_id",
                "creation_round",
                "creation_timestamp_ms",
                "creation_reason",
                "parent_seed_test_id",
                "times_selected_as_parent",
                "descendant_new_branch_hits",
                "descendant_structured_candidate_hits",
                "descendant_weak_candidate_hits",
                "descendant_weak_structured_candidate_hits",
                "descendant_weak_event_candidate_hits",
                "descendant_weak_error_log_candidate_hits",
                "descendant_strong_trace_hits",
                "branch_novelty_class");
    }

    public String toCsvRow() {
        StringBuilder sb = new StringBuilder();
        sb.append(seedTestId).append(',');
        sb.append(creationRound).append(',');
        sb.append(creationTimestampMs).append(',');
        sb.append(creationReason.name()).append(',');
        sb.append(parentSeedTestId).append(',');
        sb.append(timesSelectedAsParent.get()).append(',');
        sb.append(descendantNewBranchHits.get()).append(',');
        sb.append(descendantStructuredCandidateHits.get()).append(',');
        sb.append(descendantWeakCandidateHits.get()).append(',');
        sb.append(descendantWeakStructuredCandidateHits.get()).append(',');
        sb.append(descendantWeakEventCandidateHits.get()).append(',');
        sb.append(descendantWeakErrorLogCandidateHits.get()).append(',');
        sb.append(descendantStrongTraceHits.get()).append(',');
        sb.append(branchNoveltyClass.name());
        return sb.toString();
    }
}
