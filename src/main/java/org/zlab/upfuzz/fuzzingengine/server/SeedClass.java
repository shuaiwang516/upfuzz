package org.zlab.upfuzz.fuzzingengine.server;

/**
 * Phase 2 retention class for a seed stored in {@link RollingSeedCorpus}.
 *
 * <p>The Apr 12 mode-5 campaign showed trace-only seeds dominating the rolling
 * corpus (save rate 0.85) and crowding out branch-novel seeds. Phase 2 splits
 * the corpus into tiered pools so branch-backed seeds always have reserved
 * capacity and trace-only seeds must earn their way into the long-lived pool
 * via a probation period.
 *
 * <ul>
 *   <li>{@link #BRANCH_BACKED} — admitted due to new branch coverage
 *       (with or without an accompanying trace signal). Always retained and
 *       never evicted by Phase 2 logic.</li>
 *   <li>{@link #TRACE_PROBATION} — admitted due to trace signals alone
 *       (tri-diff exclusive or window/aggregate similarity). Lives in the
 *       probation pool until it pays off downstream. If it never does, it
 *       is evicted after the configured timeout or selection budget.</li>
 *   <li>{@link #TRACE_PROMOTED} — a formerly-probation seed that has
 *       produced a downstream branch hit, a structured tri-lane candidate,
 *       or enough independent rediscoveries. Treated as a long-lived seed
 *       and mixed with {@link #BRANCH_BACKED} during weighted parent
 *       selection.</li>
 * </ul>
 */
public enum SeedClass {
    BRANCH_BACKED, TRACE_PROBATION, TRACE_PROMOTED;
}
