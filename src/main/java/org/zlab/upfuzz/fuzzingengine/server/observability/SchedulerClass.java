package org.zlab.upfuzz.fuzzingengine.server.observability;

/**
 * Phase 3 scheduler lane assigned to a queued plan.
 *
 * <p>This label is the <em>internal</em> scheduler class chosen by
 * {@code TestPlanCorpus}. It differs from {@link QueuePriorityClass}
 * in two ways:
 *
 * <ul>
 *   <li>{@link QueuePriorityClass} is the admission-facing label — it
 *       describes <em>why</em> a plan was admitted (branch only,
 *       branch + strong trace, trace-only weak, etc.) and is stamped
 *       at admission time.</li>
 *   <li>{@link SchedulerClass} is the <em>scheduling</em> label — it
 *       describes <em>where</em> the plan lives in the Phase 3
 *       stratified queue so observability can answer "how much
 *       budget did the repro-confirm lane burn, how much decay
 *       happened on the main-exploit lane, …" from metrics alone.</li>
 * </ul>
 *
 * <p>The two labels are not redundant. For example, a plan admitted
 * with {@code BRANCH_ONLY} priority can still land in
 * {@link #REPRO_CONFIRM} if it was promoted as a strong structured
 * candidate parent, and a plan admitted as
 * {@code BRANCH_AND_STRONG_TRACE} may decay down to
 * {@link #SHADOW_EVAL} after repeated dequeues with no payoff.
 *
 * <p>Ordering is meaningful: enum ordinal reflects priority, so the
 * scheduler can iterate highest-to-lowest without a bespoke
 * comparator. {@link #REPRO_CONFIRM} is the strict-priority lane for
 * strong structured candidate parents; the remaining three lanes are
 * selected via weighted round-robin.
 */
public enum SchedulerClass {
    /** Strict-priority lane for strong structured candidate parents. */
    REPRO_CONFIRM,
    /** Weighted-RR main lane for branch + strong-trace admissions. */
    MAIN_EXPLOIT,
    /** Weighted-RR lane for pure branch-only admissions. */
    BRANCH_SCOUT,
    /** Weighted-RR low-budget lane for weak trace evidence. */
    SHADOW_EVAL;
}
