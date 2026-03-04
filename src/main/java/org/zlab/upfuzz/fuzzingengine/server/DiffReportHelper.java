package org.zlab.upfuzz.fuzzingengine.server;

/**
 * Canonical report header/metadata helpers for differential execution reports.
 *
 * Every checker in the mode-3/mode-5 path should use these helpers to produce
 * uniform, machine-parseable report headers and metadata blocks.
 */
public class DiffReportHelper {

    /** Checker type tags — used in report filenames and headers. */
    public enum CheckerType {
        EVENT_CRASH("event_crash"), CROSS_CLUSTER_INCONSISTENCY(
                "inconsistency_crosscluster"), ERROR_LOG(
                        "error_log"), LANE_COLLECTION_FAILURE(
                                "lane_collection_failure");

        public final String tag;

        CheckerType(String tag) {
            this.tag = tag;
        }
    }

    /**
     * Build a canonical metadata block for a report.
     *
     * @param checkerType the checker that produced this report
     * @param lane        lane name (e.g. "OnlyOld", "Rolling", "OnlyNew"),
     *                    or null for cross-cluster checks
     * @param verdict     the DiffVerdict applied
     * @param testID      test packet ID
     * @param configIdx   config file name / index
     * @param executionId executor ID (nullable)
     * @return multi-line metadata block string
     */
    public static String buildMetadataBlock(CheckerType checkerType,
            String lane, DiffVerdict verdict, int testID,
            String configIdx, String executionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- metadata ---\n");
        sb.append("checkerType = ").append(checkerType.tag).append("\n");
        if (lane != null) {
            sb.append("lane = ").append(lane).append("\n");
        }
        sb.append("verdict = ").append(verdict.name()).append("\n");
        sb.append("testID = ").append(testID).append("\n");
        if (configIdx != null) {
            sb.append("configIdx = ").append(configIdx).append("\n");
        }
        if (executionId != null) {
            sb.append("executionId = ").append(executionId).append("\n");
        }
        sb.append("--- end metadata ---\n");
        return sb.toString();
    }

    /**
     * Build a canonical report header for cross-cluster structured
     * inconsistency.
     */
    public static String crossClusterHeader() {
        return "[Cross-cluster inconsistency detected (structured)]\n";
    }

    /**
     * Build a canonical report header for event crash.
     *
     * @param lane lane name
     */
    public static String eventCrashHeader(String lane) {
        return "[" + lane + "] [Event execution failure]\n";
    }

    /**
     * Build a canonical report header for error log signal.
     *
     * @param lane lane name
     */
    public static String errorLogHeader(String lane) {
        return "[" + lane + "] [ERROR LOG]\n";
    }

    /**
     * Build a canonical report header for lane collection failure.
     *
     * @param lane lane name
     */
    public static String laneCollectionHeader(String lane) {
        return "[" + lane + "] [Lane collection failure]\n";
    }

    /**
     * Generate a report filename that encodes checker type, lane, and testID.
     * Avoids overwrite collisions when multiple lanes/checkers hit the same
     * testID.
     *
     * @param checkerType the checker producing this report
     * @param testID      test packet ID
     * @param lane        lane tag (nullable, e.g. "OnlyOld")
     * @return filename like "event_crash_42_Rolling.report"
     */
    public static String reportFileName(CheckerType checkerType, int testID,
            String lane) {
        String laneTag = lane == null ? "" : "_" + sanitizeLaneTag(lane);
        return checkerType.tag + "_" + testID + laneTag + ".report";
    }

    private static String sanitizeLaneTag(String lane) {
        return lane.replace(" ", "");
    }
}
