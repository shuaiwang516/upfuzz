package org.zlab.upfuzz.fuzzingengine.server.observability;

import java.util.Locale;

/**
 * Phase 5 raw classifier-input row, one per (round, window_ordinal, lane,
 * rpcService, rpcMethod, messageType, payloadType, messageKind, protocol).
 * Carries the per-tuple {@code count} observed in that lane plus the
 * {@code currentFamily} the live classifier assigned at fuzzing time.
 *
 * <p>Emitted into {@code trace_window_classifier_inputs.csv} when
 * {@link org.zlab.upfuzz.fuzzingengine.Config.Configuration#enableTraceFlowTupleDump}
 * is {@code true}. The Phase 5 {@code TraceReplay} tool consumes this CSV to
 * exercise a different version-aware family-map profile against the same raw
 * classifier inputs the live run observed.
 *
 * <p>Lane labels are {@code oo} (old-old baseline), {@code ro} (rolling),
 * {@code nn} (new-new baseline). Tuple fields may be empty strings when
 * the live runtime did not populate the corresponding metadata.
 */
public final class ClassifierInputRow {
    public final long round;
    public final int testPacketId;
    public final int windowOrdinal;
    public final String comparisonStage;
    public final String lane;
    public final String protocol;
    public final String rpcService;
    public final String rpcMethod;
    public final String messageType;
    public final String messageKind;
    public final String payloadType;
    public final String currentFamily;
    public final int count;

    public ClassifierInputRow(long round, int testPacketId, int windowOrdinal,
            String comparisonStage, String lane, String protocol,
            String rpcService, String rpcMethod, String messageType,
            String messageKind, String payloadType, String currentFamily,
            int count) {
        this.round = round;
        this.testPacketId = testPacketId;
        this.windowOrdinal = windowOrdinal;
        this.comparisonStage = orEmpty(comparisonStage);
        this.lane = orEmpty(lane);
        this.protocol = orEmpty(protocol);
        this.rpcService = orEmpty(rpcService);
        this.rpcMethod = orEmpty(rpcMethod);
        this.messageType = orEmpty(messageType);
        this.messageKind = orEmpty(messageKind);
        this.payloadType = orEmpty(payloadType);
        this.currentFamily = orEmpty(currentFamily);
        this.count = count;
    }

    public static String csvHeader() {
        return "round,test_packet_id,window_ordinal,comparison_stage,lane,"
                + "protocol,rpc_service,rpc_method,message_type,message_kind,"
                + "payload_type,current_family,count";
    }

    public String toCsvRow() {
        StringBuilder sb = new StringBuilder();
        sb.append(round).append(',');
        sb.append(testPacketId).append(',');
        sb.append(windowOrdinal).append(',');
        sb.append(csvEscape(comparisonStage)).append(',');
        sb.append(csvEscape(lane)).append(',');
        sb.append(csvEscape(protocol)).append(',');
        sb.append(csvEscape(rpcService)).append(',');
        sb.append(csvEscape(rpcMethod)).append(',');
        sb.append(csvEscape(messageType)).append(',');
        sb.append(csvEscape(messageKind)).append(',');
        sb.append(csvEscape(payloadType)).append(',');
        sb.append(csvEscape(currentFamily)).append(',');
        sb.append(count);
        return sb.toString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String csvEscape(String f) {
        if (f == null || f.isEmpty()) {
            return "";
        }
        if (f.indexOf(',') < 0 && f.indexOf('"') < 0 && f.indexOf('\n') < 0) {
            return f;
        }
        return '"' + f.replace("\"", "\"\"") + '"';
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "ClassifierInputRow{round=%d, window=%d, lane=%s, family=%s, count=%d}",
                round, windowOrdinal, lane, currentFamily, count);
    }
}
