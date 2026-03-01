package org.zlab.upfuzz.fuzzingengine.server;

/**
 * Classification of a differential test plan outcome.
 *
 * Used by the verdict router to separate real rolling-upgrade bug candidates
 * from same-version bugs and noise, reducing false positives.
 */
public enum DiffVerdict {
    /** Rolling lane diverges from BOTH baselines — potential upgrade bug. */
    ROLLING_UPGRADE_BUG_CANDIDATE,
    /** Signal appears in one or both baselines but NOT rolling-only. */
    SAME_VERSION_BUG,
    /** Signal in all three lanes — likely a test / oracle issue. */
    ORACLE_NOISE,
    /** Infrastructure issue (lane collection failure, timeout, etc.). */
    INFRA_NOISE,
    /** No anomaly detected. */
    NONE;

    /** Returns true if this verdict is more severe than {@code other}. */
    public boolean moreSignificantThan(DiffVerdict other) {
        return this.ordinal() < other.ordinal();
    }
}
