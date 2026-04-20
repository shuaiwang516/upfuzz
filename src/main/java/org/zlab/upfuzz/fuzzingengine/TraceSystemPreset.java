package org.zlab.upfuzz.fuzzingengine;

/**
 * Phase 5 system-level scoring and routing preset.
 *
 * <p>
 * Presets bundle the trace-guidance scoring thresholds, family weights,
 * background caps, trace-only admission budget, and queue-routing weights
 * into a single per-system policy so the runner does not have to materialize
 * every individual knob. {@link #applyTo(Config.Configuration)} mutates the
 * supplied configuration in place; {@link Config#setInstance(Config.Configuration)}
 * calls it after {@link Config.Configuration#normalizeModeFlags()} so the
 * resolved values reach every consumer (server, client, replay).
 *
 * <p>
 * Presets are an explicit layer on top of the Phase 3/4 calibration defaults.
 * The {@link #GENERIC} value preserves the existing Java defaults exactly —
 * use it (or set {@link Config.Configuration#traceSystemPreset} to
 * {@code GENERIC} via JSON) to opt out of preset overrides and keep
 * per-knob tuning. {@link #AUTO} resolves to the system-specific preset
 * based on {@link Config.Configuration#system}; if {@code system} is null
 * or unknown, {@link #AUTO} resolves to {@link #GENERIC}.
 *
 * <p>
 * Per-system rationale (see Phase 5 plan section "Required first-cut preset
 * content"):
 * <ul>
 * <li>{@link #CASSANDRA}: gossip / membership chatter is dominant background
 *     traffic. Background cap is strict, family weight on BACKGROUND is low,
 *     trace-only admission is parked at zero, and lower-confidence trace
 *     admission stays off until replay shows support-backed wins.</li>
 * <li>{@link #HDFS}: heartbeat-heavy background but the protocol identity
 *     (RPC protocol + method) is comparatively clean. Strong threshold is
 *     relaxed slightly, lower-confidence trace admission is enabled with a
 *     bounded per-round / per-100-round budget, and HDFS is the first
 *     candidate for live trace-backed routing.</li>
 * <li>{@link #HBASE}: scanner traffic is dense and inflates background. The
 *     background cap is strict, trace-only admission stays off until
 *     scanner-collapse is verified live, and the strong threshold matches
 *     Cassandra's stricter setting.</li>
 * </ul>
 */
public enum TraceSystemPreset {
    /**
     * Resolve to the system-specific preset based on
     * {@link Config.Configuration#system}. Falls back to {@link #GENERIC}
     * when {@code system} is null, blank, or not recognized.
     */
    AUTO,

    /**
     * No preset: keep the Java defaults from {@link Config.Configuration}.
     * Use this for ablation runs or when an experiment overrides individual
     * knobs from JSON.
     */
    GENERIC,

    CASSANDRA, HDFS, HBASE;

    /**
     * Resolve a possibly-{@link #AUTO} preset to a concrete one. Never
     * returns {@link #AUTO}.
     *
     * @param system value of {@link Config.Configuration#system} (may be
     *               {@code null}); recognized values are
     *               {@code "cassandra"} / {@code "hdfs"} / {@code "hbase"}
     *               (case-insensitive).
     * @return the system-specific preset when {@code this == AUTO} and the
     *         system is recognized; {@link #GENERIC} otherwise; or
     *         {@code this} when not {@link #AUTO}.
     */
    public TraceSystemPreset resolve(String system) {
        if (this != AUTO) {
            return this;
        }
        if (system == null) {
            return GENERIC;
        }
        switch (system.trim().toLowerCase()) {
        case "cassandra":
            return CASSANDRA;
        case "hdfs":
            return HDFS;
        case "hbase":
            return HBASE;
        default:
            return GENERIC;
        }
    }

    /**
     * Apply preset values to {@code conf}. {@link #GENERIC} is a no-op so
     * the caller can call {@link #applyTo(Config.Configuration)}
     * unconditionally. {@link #AUTO} resolves itself first; callers that
     * have already resolved should pass the concrete preset directly.
     */
    public void applyTo(Config.Configuration conf) {
        TraceSystemPreset resolved = resolve(conf == null ? null : conf.system);
        switch (resolved) {
        case CASSANDRA:
            applyCassandra(conf);
            break;
        case HDFS:
            applyHdfs(conf);
            break;
        case HBASE:
            applyHbase(conf);
            break;
        case GENERIC:
        default:
            break;
        }
    }

    // -------------------------------------------------------------------
    // Cassandra preset.
    //
    // Initial policy from Phase 5 plan:
    // - keep trace-only admission effectively off until replay shows
    // support-backed wins;
    // - keep background cap strict because membership chatter is common;
    // - use conservative routing into SHADOW_EVAL until replay shows
    // support-backed wins.
    // -------------------------------------------------------------------
    private static void applyCassandra(Config.Configuration c) {
        c.traceUpgradeCriticalFamilyWeight = 1.0;
        c.traceBackgroundFamilyWeight = 0.15;
        c.traceUnknownFamilyWeight = 0.40;
        c.traceStrongScoreThreshold = 0.40;
        c.traceWeakScoreThreshold = 0.10;
        c.traceBoundaryBonus = 0.15;
        c.traceOrderBonusCap = 0.10;
        c.traceBackgroundCap = 0.08;
        c.traceMinBaselineAgreementForStrong = 0.55;
        c.traceMinRollingDivergenceForStrong = 0.20;

        c.traceOnlyAdmissionCapPerRound = 0;
        c.traceOnlyAdmissionCapPer100Rounds = 0;
        c.enableLowerConfidenceTraceAdmission = false;

        c.mainExploitQueueWeight = 8;
        c.branchScoutQueueWeight = 4;
        c.shadowEvalQueueWeight = 1;
        c.reproConfirmQueueWeight = 4;
    }

    // -------------------------------------------------------------------
    // HDFS preset.
    //
    // Initial policy from Phase 5 plan:
    // - HDFS is the first candidate where bounded trace-backed routing
    // may be enabled after replay;
    // - keep heartbeat-heavy background traffic capped even if support
    // is dense.
    // -------------------------------------------------------------------
    private static void applyHdfs(Config.Configuration c) {
        c.traceUpgradeCriticalFamilyWeight = 1.0;
        c.traceBackgroundFamilyWeight = 0.15;
        c.traceUnknownFamilyWeight = 0.40;
        c.traceStrongScoreThreshold = 0.32;
        c.traceWeakScoreThreshold = 0.10;
        c.traceBoundaryBonus = 0.15;
        c.traceOrderBonusCap = 0.10;
        c.traceBackgroundCap = 0.10;
        c.traceMinBaselineAgreementForStrong = 0.55;
        c.traceMinRollingDivergenceForStrong = 0.20;

        c.traceOnlyAdmissionCapPerRound = 1;
        c.traceOnlyAdmissionCapPer100Rounds = 10;
        c.enableLowerConfidenceTraceAdmission = true;

        c.mainExploitQueueWeight = 8;
        c.branchScoutQueueWeight = 3;
        c.shadowEvalQueueWeight = 2;
        c.reproConfirmQueueWeight = 4;
    }

    // -------------------------------------------------------------------
    // HBase preset.
    //
    // Special handling from Phase 5 plan:
    // - scanner collapse must be on before live use (handled in the
    // instrumentation bridge — see Phase 2 scanner priority fix);
    // - trace-only admission should stay off initially.
    // -------------------------------------------------------------------
    private static void applyHbase(Config.Configuration c) {
        c.traceUpgradeCriticalFamilyWeight = 1.0;
        c.traceBackgroundFamilyWeight = 0.15;
        c.traceUnknownFamilyWeight = 0.40;
        c.traceStrongScoreThreshold = 0.40;
        c.traceWeakScoreThreshold = 0.10;
        c.traceBoundaryBonus = 0.15;
        c.traceOrderBonusCap = 0.10;
        c.traceBackgroundCap = 0.08;
        c.traceMinBaselineAgreementForStrong = 0.55;
        c.traceMinRollingDivergenceForStrong = 0.20;

        c.traceOnlyAdmissionCapPerRound = 0;
        c.traceOnlyAdmissionCapPer100Rounds = 0;
        c.enableLowerConfidenceTraceAdmission = false;

        c.mainExploitQueueWeight = 8;
        c.branchScoutQueueWeight = 4;
        c.shadowEvalQueueWeight = 1;
        c.reproConfirmQueueWeight = 4;
    }
}
