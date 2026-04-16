package org.zlab.upfuzz.fuzzingengine;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jacoco.core.data.ExecutionDataStore;

public class FeedBack {
    public ExecutionDataStore originalCodeCoverage;
    public ExecutionDataStore upgradedCodeCoverage;
    public ExecutionDataStore downgradedCodeCoverage;

    /**
     * Phase 5 stage coverage snapshots. Keyed by boundary label
     * (e.g. "AFTER_UPGRADE", "AFTER_FINALIZE"). Each value is the
     * merged upgraded-version coverage collected at that boundary.
     * Only populated on the rolling lane when
     * {@code enableStageCoverageSnapshots} is true.
     */
    public Map<String, ExecutionDataStore> stageCoverageSnapshots;

    public FeedBack() {
        originalCodeCoverage = new ExecutionDataStore();
        upgradedCodeCoverage = new ExecutionDataStore();
        downgradedCodeCoverage = new ExecutionDataStore();
        stageCoverageSnapshots = new LinkedHashMap<>();
    }
}
