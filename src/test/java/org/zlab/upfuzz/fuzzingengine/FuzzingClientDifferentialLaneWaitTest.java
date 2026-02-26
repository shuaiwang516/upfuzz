package org.zlab.upfuzz.fuzzingengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.zlab.upfuzz.fuzzingengine.packet.TestPlanFeedbackPacket;
import org.zlab.upfuzz.fuzzingengine.packet.TestPlanPacket;
import org.zlab.upfuzz.fuzzingengine.testplan.TestPlan;

public class FuzzingClientDifferentialLaneWaitTest {
    private static TestPlanPacket buildMinimalTestPlanPacket() {
        TestPlan testPlan = new TestPlan(
                1,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        return new TestPlanPacket("test-system", 1, "test-config", testPlan);
    }

    private static TestPlanFeedbackPacket buildSuccessfulLaneFeedback() {
        FeedBack[] feedBacks = new FeedBack[] { new FeedBack() };
        return new TestPlanFeedbackPacket("test-system", "test-config", 1,
                feedBacks);
    }

    @Test
    public void rollingTimeoutShouldNotForceOnlyNewTimeout() {
        TestPlanPacket packet = buildMinimalTestPlanPacket();

        CompletableFuture<TestPlanFeedbackPacket> oldFuture = CompletableFuture
                .completedFuture(buildSuccessfulLaneFeedback());
        CompletableFuture<TestPlanFeedbackPacket> rollingFuture = new CompletableFuture<>();
        CompletableFuture<TestPlanFeedbackPacket> newFuture = CompletableFuture
                .completedFuture(buildSuccessfulLaneFeedback());

        Map<String, Future<TestPlanFeedbackPacket>> laneFutures = new LinkedHashMap<>();
        laneFutures.put("OnlyOld", oldFuture);
        laneFutures.put("Rolling", rollingFuture);
        laneFutures.put("OnlyNew", newFuture);

        TestPlanFeedbackPacket[] packets = FuzzingClient
                .collectDifferentialFeedbackPackets(
                        packet,
                        laneFutures,
                        System.currentTimeMillis(),
                        100L);

        assertEquals(TestPlanFeedbackPacket.LaneStatus.OK,
                packets[0].laneStatus);
        assertEquals(TestPlanFeedbackPacket.LaneStatus.TIMEOUT,
                packets[1].laneStatus);
        assertEquals(TestPlanFeedbackPacket.LaneStatus.OK,
                packets[2].laneStatus);
        assertFalse(packets[1].isEventFailed);
    }

    @Test
    public void laneExecutionExceptionShouldBeClassifiedWithoutEventFailureBit() {
        TestPlanPacket packet = buildMinimalTestPlanPacket();

        CompletableFuture<TestPlanFeedbackPacket> oldFuture = CompletableFuture
                .completedFuture(buildSuccessfulLaneFeedback());
        CompletableFuture<TestPlanFeedbackPacket> rollingFuture = new CompletableFuture<>();
        rollingFuture.completeExceptionally(new RuntimeException("boom"));
        CompletableFuture<TestPlanFeedbackPacket> newFuture = CompletableFuture
                .completedFuture(buildSuccessfulLaneFeedback());

        Map<String, Future<TestPlanFeedbackPacket>> laneFutures = new LinkedHashMap<>();
        laneFutures.put("OnlyOld", oldFuture);
        laneFutures.put("Rolling", rollingFuture);
        laneFutures.put("OnlyNew", newFuture);

        TestPlanFeedbackPacket[] packets = FuzzingClient
                .collectDifferentialFeedbackPackets(
                        packet,
                        laneFutures,
                        System.currentTimeMillis(),
                        300L);

        assertEquals(TestPlanFeedbackPacket.LaneStatus.EXCEPTION,
                packets[1].laneStatus);
        assertTrue(packets[1].laneFailureReason.contains("[Rolling]"));
        assertTrue(
                packets[1].laneFailureReason.contains("[EXCEPTION]"));
        assertFalse(packets[1].isEventFailed);
    }
}
