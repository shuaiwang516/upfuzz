package org.zlab.upfuzz.fuzzingengine.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.upfuzz.CommandPool;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.testplan.TestPlan;
import org.zlab.upfuzz.fuzzingengine.testplan.event.Event;
import org.zlab.upfuzz.fuzzingengine.testplan.event.command.ShellCommand;
import org.zlab.upfuzz.fuzzingengine.testplan.event.fault.Fault;
import org.zlab.upfuzz.fuzzingengine.testplan.event.fault.FaultRecover;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.FinalizeUpgrade;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.PrepareUpgrade;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.UpgradeOp;
import org.zlab.upfuzz.utils.Pair;

import static org.zlab.upfuzz.utils.Utilities.rand;

/**
 * Phase 4 stage-focused mutator. Inspects a parent test plan's
 * {@link StageMutationHint} and chooses a specialized mutation family
 * that concentrates changes near the stage where the parent was
 * admitted. Falls back to
 * {@link TestPlan#mutate(CommandPool, Class)} when no hint is present
 * or when every stage-aware family fails.
 *
 * <p>The mutator deliberately does not re-implement generic
 * command-sequence mutation — it only adjusts placement and local
 * structure. Command-level mutation is still owned by
 * {@code TestPlan.mutate(...)} and is reached either by chaining
 * (stage-aware placement then a generic fallback) or by a direct
 * fallback call when no hint is available.
 *
 * <p>All mutators return {@code true} on success (they changed the
 * plan in a way that the verifier is expected to accept) and
 * {@code false} when they could not find a valid operation. The
 * caller is responsible for validating the result with
 * {@link FuzzingServer#testPlanVerifier(List, int)} before enqueuing
 * — failures here simply trigger the next mutator or the generic
 * fallback.
 */
public final class StageAwareTestPlanMutator {
    private static final Logger logger = LogManager
            .getLogger(StageAwareTestPlanMutator.class);

    /** Single mutation family the mutator can dispatch to. */
    public enum MutationFamily {
        WORKLOAD_BURST_BEFORE_FIRST_UPGRADE, WORKLOAD_BURST_AFTER_FIRST_UPGRADE, WORKLOAD_BURST_NEAR_POST_FINAL, REPEATED_VALIDATION_AFTER_HOTSPOT, ADJUST_INTERVAL_AROUND_HOTSPOT, DUPLICATE_SHELL_CLUSTER_AT_BOUNDARY, MOVE_VALIDATION_AFTER_FINALIZE, LOCAL_REORDER_NEAR_HOTSPOT, SHUFFLE_UPGRADE_ORDER, TEMPLATE_WRITE_HEAVY_PRE_UPGRADE, TEMPLATE_VALIDATION_HEAVY_AFTER_FINALIZE, TEMPLATE_BOUNDARY_PRESSURE, TEMPLATE_FAULT_PLUS_UPGRADE,
    }

    private StageAwareTestPlanMutator() {
    }

    /**
     * Run one mutation attempt using a stage-aware family if the hint
     * supports it, otherwise fall back to the generic
     * {@link TestPlan#mutate(CommandPool, Class)}.
     *
     * @return {@code true} if the plan was mutated in place.
     */
    public static boolean mutate(TestPlan plan, StageMutationHint hint,
            CommandPool commandPool, Class<? extends State> stateClass) {
        Config.Configuration cfg = Config.getConf();
        boolean phase4Enabled = cfg != null && cfg.enableStageFocusedMutation;
        boolean usableHint = phase4Enabled && hint != null
                && hint.hasStageInfo();
        double stageProb = cfg != null ? cfg.stageAwareMutationProbability
                : 0.0;

        if (!usableHint || rand.nextDouble() >= stageProb) {
            return plan.mutate(commandPool, stateClass);
        }

        int maxAttempts = Math.max(1,
                cfg.stageAwareMutationMaxAttempts);
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            MutationFamily family = pickFamily(hint, cfg);
            if (family == null) {
                break;
            }
            if (applyFamily(plan, hint, family, commandPool, stateClass)) {
                return true;
            }
        }
        return plan.mutate(commandPool, stateClass);
    }

    /** Test-visible: pick a mutation family deterministically given a hint. */
    static MutationFamily pickFamily(StageMutationHint hint,
            Config.Configuration cfg) {
        if (hint == null || !hint.hasStageInfo()) {
            return null;
        }
        boolean templatesEnabled = cfg != null && cfg.enableStageTemplates;
        double templateProb = cfg != null ? cfg.stageTemplateProbability
                : 0.0;
        if (templatesEnabled && rand.nextDouble() < templateProb) {
            MutationFamily templateFamily = pickTemplateFamily(hint);
            if (templateFamily != null) {
                return templateFamily;
            }
        }
        List<MutationFamily> candidates = new ArrayList<>();
        switch (hint.hotStageKind) {
        case PRE_UPGRADE:
            candidates.add(MutationFamily.WORKLOAD_BURST_BEFORE_FIRST_UPGRADE);
            candidates.add(MutationFamily.ADJUST_INTERVAL_AROUND_HOTSPOT);
            candidates.add(MutationFamily.LOCAL_REORDER_NEAR_HOTSPOT);
            // Even pre-upgrade hotspots can benefit from a template
            // that pushes pressure across the first boundary.
            candidates.add(MutationFamily.DUPLICATE_SHELL_CLUSTER_AT_BOUNDARY);
            break;
        case POST_STAGE:
            candidates.add(MutationFamily.WORKLOAD_BURST_AFTER_FIRST_UPGRADE);
            candidates.add(MutationFamily.DUPLICATE_SHELL_CLUSTER_AT_BOUNDARY);
            candidates.add(MutationFamily.REPEATED_VALIDATION_AFTER_HOTSPOT);
            candidates.add(MutationFamily.LOCAL_REORDER_NEAR_HOTSPOT);
            candidates.add(MutationFamily.ADJUST_INTERVAL_AROUND_HOTSPOT);
            break;
        case POST_FINAL_STAGE:
            candidates.add(MutationFamily.WORKLOAD_BURST_NEAR_POST_FINAL);
            candidates.add(MutationFamily.MOVE_VALIDATION_AFTER_FINALIZE);
            candidates.add(MutationFamily.REPEATED_VALIDATION_AFTER_HOTSPOT);
            candidates.add(MutationFamily.LOCAL_REORDER_NEAR_HOTSPOT);
            break;
        case FAULT_RECOVERY:
            candidates.add(MutationFamily.DUPLICATE_SHELL_CLUSTER_AT_BOUNDARY);
            candidates.add(MutationFamily.LOCAL_REORDER_NEAR_HOTSPOT);
            candidates.add(MutationFamily.ADJUST_INTERVAL_AROUND_HOTSPOT);
            break;
        case LIFECYCLE_ONLY:
        case UNKNOWN:
        default:
            candidates.add(MutationFamily.WORKLOAD_BURST_AFTER_FIRST_UPGRADE);
            candidates.add(MutationFamily.LOCAL_REORDER_NEAR_HOTSPOT);
            break;
        }
        // Shuffle-upgrade-order is only tried when the parent hint
        // says upgrade order mattered — i.e. the divergence was
        // observed in a POST_STAGE window with multiple upgraded nodes.
        if (hint.upgradeOrderMattered) {
            candidates.add(MutationFamily.SHUFFLE_UPGRADE_ORDER);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(rand.nextInt(candidates.size()));
    }

    /**
     * Template gate: the reusable template families are only eligible
     * when the hint provides enough signal to suggest they will be
     * useful. Fault-plus-upgrade requires a fault-influenced parent
     * (the phase plan's explicit condition).
     */
    private static MutationFamily pickTemplateFamily(
            StageMutationHint hint) {
        List<MutationFamily> templates = new ArrayList<>();
        switch (hint.hotStageKind) {
        case PRE_UPGRADE:
            templates.add(MutationFamily.TEMPLATE_WRITE_HEAVY_PRE_UPGRADE);
            templates.add(MutationFamily.TEMPLATE_BOUNDARY_PRESSURE);
            break;
        case POST_STAGE:
            templates.add(MutationFamily.TEMPLATE_BOUNDARY_PRESSURE);
            templates.add(
                    MutationFamily.TEMPLATE_VALIDATION_HEAVY_AFTER_FINALIZE);
            break;
        case POST_FINAL_STAGE:
            templates.add(
                    MutationFamily.TEMPLATE_VALIDATION_HEAVY_AFTER_FINALIZE);
            break;
        case FAULT_RECOVERY:
            templates.add(MutationFamily.TEMPLATE_BOUNDARY_PRESSURE);
            break;
        default:
            break;
        }
        if (hint.faultInfluenced) {
            templates.add(MutationFamily.TEMPLATE_FAULT_PLUS_UPGRADE);
        }
        if (templates.isEmpty()) {
            return null;
        }
        return templates.get(rand.nextInt(templates.size()));
    }

    static boolean applyFamily(TestPlan plan, StageMutationHint hint,
            MutationFamily family, CommandPool commandPool,
            Class<? extends State> stateClass) {
        switch (family) {
        case WORKLOAD_BURST_BEFORE_FIRST_UPGRADE:
            return moveWorkloadBurst(plan, hint,
                    BurstPlacement.BEFORE_FIRST_UPGRADE);
        case WORKLOAD_BURST_AFTER_FIRST_UPGRADE:
            return moveWorkloadBurst(plan, hint,
                    BurstPlacement.AFTER_FIRST_UPGRADE);
        case WORKLOAD_BURST_NEAR_POST_FINAL:
            return moveWorkloadBurst(plan, hint,
                    BurstPlacement.NEAR_POST_FINAL);
        case REPEATED_VALIDATION_AFTER_HOTSPOT:
            return addRepeatedValidationAfterHotspot(plan, hint);
        case ADJUST_INTERVAL_AROUND_HOTSPOT:
            return adjustIntervalAroundHotspot(plan, hint);
        case DUPLICATE_SHELL_CLUSTER_AT_BOUNDARY:
            return duplicateShellClusterAtBoundary(plan, hint);
        case MOVE_VALIDATION_AFTER_FINALIZE:
            return moveValidationAfterFinalize(plan);
        case LOCAL_REORDER_NEAR_HOTSPOT:
            return localReorderNearHotspot(plan, hint);
        case SHUFFLE_UPGRADE_ORDER:
            return shuffleUpgradeOrder(plan, hint);
        case TEMPLATE_WRITE_HEAVY_PRE_UPGRADE:
            return applyWriteHeavyPreUpgradeTemplate(plan, hint);
        case TEMPLATE_VALIDATION_HEAVY_AFTER_FINALIZE:
            return applyValidationHeavyAfterFinalizeTemplate(plan);
        case TEMPLATE_BOUNDARY_PRESSURE:
            return applyBoundaryPressureTemplate(plan, hint);
        case TEMPLATE_FAULT_PLUS_UPGRADE:
            return applyFaultPlusUpgradeTemplate(plan, hint);
        default:
            return false;
        }
    }

    // === Mutation families ===

    enum BurstPlacement {
        BEFORE_FIRST_UPGRADE, AFTER_FIRST_UPGRADE, NEAR_POST_FINAL
    }

    static boolean moveWorkloadBurst(TestPlan plan, StageMutationHint hint,
            BurstPlacement placement) {
        List<Event> events = plan.events;
        if (events == null || events.isEmpty()) {
            return false;
        }
        List<Integer> shellIdxes = indicesOf(events, ShellCommand.class);
        if (shellIdxes.isEmpty()) {
            return false;
        }
        int burstSize = Math.min(shellIdxes.size(),
                1 + rand.nextInt(Math.max(1, Math.min(shellIdxes.size(),
                        3))));
        int startIdx = shellIdxes.get(rand.nextInt(shellIdxes.size()));
        // Find a contiguous run of shell commands starting at startIdx
        // and take up to burstSize of them so we move a real "burst",
        // not one isolated command.
        List<Event> burst = new ArrayList<>();
        List<Integer> burstIdxes = new ArrayList<>();
        int walker = startIdx;
        while (walker < events.size() && burst.size() < burstSize
                && events.get(walker) instanceof ShellCommand) {
            burst.add(events.get(walker));
            burstIdxes.add(walker);
            walker++;
        }
        if (burst.isEmpty()) {
            return false;
        }
        // Remove in reverse order so earlier indices stay valid.
        for (int i = burstIdxes.size() - 1; i >= 0; i--) {
            events.remove((int) burstIdxes.get(i));
        }
        int anchor = findAnchorForPlacement(events, placement);
        if (anchor < 0 || anchor > events.size()) {
            // Re-insert where we took it so we do not leave the plan
            // in a broken state, then fail.
            int reinsert = Math.min(startIdx, events.size());
            events.addAll(reinsert, burst);
            return false;
        }
        events.addAll(anchor, burst);
        return true;
    }

    private static int findAnchorForPlacement(List<Event> events,
            BurstPlacement placement) {
        int firstUpgrade = firstIndexOf(events, UpgradeOp.class);
        int firstFinalize = firstIndexOf(events, FinalizeUpgrade.class);
        switch (placement) {
        case BEFORE_FIRST_UPGRADE:
            if (firstUpgrade < 0) {
                return 0;
            }
            return Math.max(0, firstUpgrade);
        case AFTER_FIRST_UPGRADE:
            if (firstUpgrade < 0) {
                return events.size();
            }
            return Math.min(events.size(), firstUpgrade + 1);
        case NEAR_POST_FINAL:
            if (firstFinalize >= 0) {
                return Math.min(events.size(), firstFinalize + 1);
            }
            // No explicit FinalizeUpgrade — drop near the plan tail.
            return events.size();
        default:
            return -1;
        }
    }

    static boolean addRepeatedValidationAfterHotspot(TestPlan plan,
            StageMutationHint hint) {
        List<String> validation = plan.validationCommands;
        if (validation == null || validation.isEmpty()) {
            return false;
        }
        List<Event> events = plan.events;
        if (events == null) {
            return false;
        }
        int hotspot = locateHotspotAnchor(events, hint);
        if (hotspot < 0) {
            return false;
        }
        int pickCount = 1 + rand.nextInt(Math.min(3, validation.size()));
        int repeats = 1 + rand.nextInt(2); // 1-2 repeats
        int nodeTarget = pickTargetNode(hint, plan.nodeNum);
        List<Event> appendix = new LinkedList<>();
        for (int r = 0; r < repeats; r++) {
            for (int i = 0; i < pickCount; i++) {
                String cmd = validation.get(
                        rand.nextInt(validation.size()));
                appendix.add(new ShellCommand(cmd, nodeTarget));
            }
        }
        int insertPos = Math.min(events.size(), hotspot + 1);
        events.addAll(insertPos, appendix);
        return true;
    }

    static boolean adjustIntervalAroundHotspot(TestPlan plan,
            StageMutationHint hint) {
        List<Event> events = plan.events;
        if (events == null || events.isEmpty()) {
            return false;
        }
        int hotspot = locateHotspotAnchor(events, hint);
        if (hotspot < 0) {
            hotspot = rand.nextInt(events.size());
        }
        int radius = 2;
        int lo = Math.max(0, hotspot - radius);
        int hi = Math.min(events.size() - 1, hotspot + radius);
        boolean changed = false;
        Config.Configuration cfg = Config.getConf();
        int min = cfg != null ? cfg.intervalMin : 10;
        int max = cfg != null ? cfg.intervalMax : 200;
        for (int i = lo; i <= hi; i++) {
            Event e = events.get(i);
            int newInterval = rand.nextInt(Math.max(1, max - min + 1))
                    + min;
            if (newInterval != e.interval) {
                e.interval = newInterval;
                changed = true;
            }
        }
        return changed;
    }

    static boolean duplicateShellClusterAtBoundary(TestPlan plan,
            StageMutationHint hint) {
        List<Event> events = plan.events;
        if (events == null || events.isEmpty()) {
            return false;
        }
        List<Integer> upgradeIdxes = indicesOf(events, UpgradeOp.class);
        if (upgradeIdxes.isEmpty()) {
            return false;
        }
        int boundaryIdx = upgradeIdxes.get(rand.nextInt(upgradeIdxes.size()));
        // Find a shell-command cluster adjacent to the boundary.
        int preStart = boundaryIdx - 1;
        while (preStart >= 0 && events.get(preStart) instanceof ShellCommand) {
            preStart--;
        }
        preStart++;
        int postEnd = boundaryIdx + 1;
        while (postEnd < events.size()
                && events.get(postEnd) instanceof ShellCommand) {
            postEnd++;
        }
        List<Event> cluster = new LinkedList<>();
        if (preStart < boundaryIdx) {
            for (int i = preStart; i < boundaryIdx; i++) {
                cluster.add(SerializationUtils.clone(events.get(i)));
            }
        } else if (boundaryIdx + 1 < postEnd) {
            for (int i = boundaryIdx + 1; i < postEnd; i++) {
                cluster.add(SerializationUtils.clone(events.get(i)));
            }
        }
        if (cluster.isEmpty()) {
            // No adjacent cluster — synthesise one using validation commands.
            if (plan.validationCommands == null
                    || plan.validationCommands.isEmpty()) {
                return false;
            }
            int node = pickTargetNode(hint, plan.nodeNum);
            cluster.add(new ShellCommand(plan.validationCommands
                    .get(rand.nextInt(plan.validationCommands.size())),
                    node));
        }
        int insertPos = Math.min(events.size(), boundaryIdx + 1);
        events.addAll(insertPos, cluster);
        return true;
    }

    static boolean moveValidationAfterFinalize(TestPlan plan) {
        List<Event> events = plan.events;
        if (events == null || events.isEmpty()) {
            return false;
        }
        int finalizeIdx = firstIndexOf(events, FinalizeUpgrade.class);
        if (finalizeIdx < 0) {
            return false;
        }
        // Pull validation-like shell commands (by matching the plan's
        // validation list) from *before* finalize and move them to
        // immediately after it.
        List<String> validation = plan.validationCommands;
        if (validation == null || validation.isEmpty()) {
            return false;
        }
        List<Event> pulled = new LinkedList<>();
        for (int i = finalizeIdx - 1; i >= 0; i--) {
            Event e = events.get(i);
            if (e instanceof ShellCommand) {
                String cmd = ((ShellCommand) e).getCommand();
                if (cmd != null && validation.contains(cmd)) {
                    pulled.add(0, events.remove(i));
                    finalizeIdx--;
                }
            }
        }
        if (pulled.isEmpty()) {
            return false;
        }
        events.addAll(Math.min(events.size(), finalizeIdx + 1), pulled);
        return true;
    }

    static boolean localReorderNearHotspot(TestPlan plan,
            StageMutationHint hint) {
        List<Event> events = plan.events;
        if (events == null || events.size() < 2) {
            return false;
        }
        int hotspot = locateHotspotAnchor(events, hint);
        if (hotspot < 0) {
            hotspot = rand.nextInt(events.size());
        }
        int radius = 2;
        int lo = Math.max(0, hotspot - radius);
        int hi = Math.min(events.size() - 1, hotspot + radius);
        if (hi - lo < 1) {
            return false;
        }
        // Swap two ShellCommand events within [lo, hi]. Only shell
        // commands are eligible — upgrade lifecycle events, faults,
        // and fault recovers are excluded so the upgrade skeleton and
        // fault ordering stay intact.
        for (int tries = 0; tries < 6; tries++) {
            int a = lo + rand.nextInt(hi - lo + 1);
            int b = lo + rand.nextInt(hi - lo + 1);
            if (a == b) {
                continue;
            }
            Event ea = events.get(a);
            Event eb = events.get(b);
            if (!(ea instanceof ShellCommand)
                    || !(eb instanceof ShellCommand)) {
                continue;
            }
            events.set(a, eb);
            events.set(b, ea);
            return true;
        }
        return false;
    }

    static boolean shuffleUpgradeOrder(TestPlan plan, StageMutationHint hint) {
        Config.Configuration cfg = Config.getConf();
        if (cfg != null && !cfg.shuffleUpgradeOrder) {
            return false;
        }
        List<Event> events = plan.events;
        if (events == null) {
            return false;
        }
        List<Integer> upgradeIdxes = indicesOf(events, UpgradeOp.class);
        if (upgradeIdxes.size() < 2) {
            return false;
        }
        int i = upgradeIdxes.get(rand.nextInt(upgradeIdxes.size()));
        int j = upgradeIdxes.get(rand.nextInt(upgradeIdxes.size()));
        if (i == j) {
            return false;
        }
        UpgradeOp a = (UpgradeOp) events.get(i);
        UpgradeOp b = (UpgradeOp) events.get(j);
        int tmp = a.nodeIndex;
        a.nodeIndex = b.nodeIndex;
        b.nodeIndex = tmp;
        return true;
    }

    // === Templates ===

    private static boolean applyWriteHeavyPreUpgradeTemplate(TestPlan plan,
            StageMutationHint hint) {
        List<Event> events = plan.events;
        if (events == null) {
            return false;
        }
        int firstUpgrade = firstIndexOf(events, UpgradeOp.class);
        int insertPos = firstUpgrade < 0 ? events.size() : firstUpgrade;
        return appendClonedWriteCommands(plan, insertPos, hint, 3);
    }

    private static boolean applyValidationHeavyAfterFinalizeTemplate(
            TestPlan plan) {
        List<Event> events = plan.events;
        if (events == null) {
            return false;
        }
        int finalizeIdx = firstIndexOf(events, FinalizeUpgrade.class);
        int anchor;
        if (finalizeIdx >= 0) {
            anchor = Math.min(events.size(), finalizeIdx + 1);
        } else {
            anchor = events.size();
        }
        List<String> validation = plan.validationCommands;
        if (validation == null || validation.isEmpty()) {
            return false;
        }
        List<Event> appendix = new LinkedList<>();
        int count = Math.min(4, validation.size());
        for (int i = 0; i < count; i++) {
            appendix.add(new ShellCommand(validation.get(
                    rand.nextInt(validation.size())), 0));
        }
        events.addAll(anchor, appendix);
        return true;
    }

    private static boolean applyBoundaryPressureTemplate(TestPlan plan,
            StageMutationHint hint) {
        List<Event> events = plan.events;
        if (events == null) {
            return false;
        }
        List<Integer> upgradeIdxes = indicesOf(events, UpgradeOp.class);
        if (upgradeIdxes.isEmpty()) {
            return false;
        }
        int boundary = upgradeIdxes.get(0);
        boolean added = appendClonedWriteCommands(plan,
                Math.max(0, boundary), hint, 2);
        boolean postAdded = appendClonedWriteCommands(plan,
                Math.min(plan.events.size(), boundary + 2), hint, 2);
        return added || postAdded;
    }

    private static boolean applyFaultPlusUpgradeTemplate(TestPlan plan,
            StageMutationHint hint) {
        Config.Configuration cfg = Config.getConf();
        if (cfg == null) {
            return false;
        }
        List<Event> events = plan.events;
        if (events == null) {
            return false;
        }
        List<Integer> upgradeIdxes = indicesOf(events, UpgradeOp.class);
        if (upgradeIdxes.isEmpty()) {
            return false;
        }
        int boundary = upgradeIdxes.get(0);
        Pair<Fault, FaultRecover> faultPair = Fault
                .randomGenerateFault(cfg.nodeNum);
        if (faultPair == null) {
            return false;
        }
        events.add(Math.max(0, boundary), faultPair.left);
        if (faultPair.right != null) {
            int recoverPos = Math.min(events.size(), boundary + 2);
            events.add(recoverPos, faultPair.right);
        }
        return true;
    }

    // === Helpers ===

    /**
     * Clone existing write/workload shell commands from the plan's
     * event list and insert them at the given anchor. Only shell
     * commands that are NOT in the plan's validationCommands set are
     * considered write-type — this avoids the mistake of treating
     * read/validation queries as write-heavy workload.
     */
    private static boolean appendClonedWriteCommands(TestPlan plan,
            int anchor, StageMutationHint hint, int count) {
        List<Event> events = plan.events;
        if (events == null || count <= 0) {
            return false;
        }
        Set<String> validationSet = plan.validationCommands == null
                ? Collections.emptySet()
                : new HashSet<>(plan.validationCommands);
        List<ShellCommand> writeCommands = new ArrayList<>();
        for (Event e : events) {
            if (e instanceof ShellCommand) {
                ShellCommand sc = (ShellCommand) e;
                if (sc.getCommand() != null
                        && !validationSet.contains(sc.getCommand())) {
                    writeCommands.add(sc);
                }
            }
        }
        if (writeCommands.isEmpty()) {
            return false;
        }
        int nodeTarget = pickTargetNode(hint, plan.nodeNum);
        List<Event> burst = new LinkedList<>();
        for (int i = 0; i < count; i++) {
            ShellCommand source = writeCommands
                    .get(rand.nextInt(writeCommands.size()));
            burst.add(new ShellCommand(source.getCommand(), nodeTarget));
        }
        int insertPos = Math.max(0, Math.min(events.size(), anchor));
        events.addAll(insertPos, burst);
        return true;
    }

    /**
     * Map the hint's firing window ordinal back to an event index in
     * the plan. The hint stores the rolling lane's window ordinal,
     * which does not directly map to a plan index — but the first
     * {@link UpgradeOp} / {@link FinalizeUpgrade} boundary gives us
     * an upper bound, and the hot node set tells us which shell
     * commands are likely relevant. Pick the first shell command
     * belonging to a hot node within the corresponding half of the
     * plan.
     */
    static int locateHotspotAnchor(List<Event> events,
            StageMutationHint hint) {
        if (events == null || events.isEmpty()) {
            return -1;
        }
        int firstUpgrade = firstIndexOf(events, UpgradeOp.class);
        int finalizeIdx = firstIndexOf(events, FinalizeUpgrade.class);
        int lo;
        int hi;
        if (hint == null) {
            lo = 0;
            hi = events.size() - 1;
        } else {
            switch (hint.hotStageKind) {
            case PRE_UPGRADE:
                lo = 0;
                hi = firstUpgrade < 0 ? events.size() - 1
                        : firstUpgrade;
                break;
            case POST_STAGE:
                lo = firstUpgrade < 0 ? 0 : firstUpgrade;
                hi = finalizeIdx < 0 ? events.size() - 1 : finalizeIdx;
                break;
            case POST_FINAL_STAGE:
                lo = finalizeIdx < 0
                        ? (firstUpgrade < 0 ? 0 : firstUpgrade)
                        : finalizeIdx;
                hi = events.size() - 1;
                break;
            case FAULT_RECOVERY:
            case LIFECYCLE_ONLY:
            case UNKNOWN:
            default:
                lo = 0;
                hi = events.size() - 1;
                break;
            }
        }
        if (lo > hi) {
            return -1;
        }
        // Prefer a shell command belonging to a hot node within the range.
        if (hint != null && hint.hotNodeSet != null
                && !hint.hotNodeSet.isEmpty()) {
            for (int i = lo; i <= hi; i++) {
                Event e = events.get(i);
                if (e instanceof ShellCommand
                        && hint.hotNodeSet.contains(
                                ((ShellCommand) e).getNodeIndex())) {
                    return i;
                }
            }
        }
        // Fall back to the first shell command in range, otherwise the
        // middle of the range.
        for (int i = lo; i <= hi; i++) {
            if (events.get(i) instanceof ShellCommand) {
                return i;
            }
        }
        return Math.min(events.size() - 1, (lo + hi) / 2);
    }

    private static int pickTargetNode(StageMutationHint hint, int nodeNum) {
        if (hint != null && hint.hotNodeSet != null
                && !hint.hotNodeSet.isEmpty()) {
            int idx = rand.nextInt(hint.hotNodeSet.size());
            int i = 0;
            for (int node : hint.hotNodeSet) {
                if (i++ == idx && node >= 0 && node < nodeNum) {
                    return node;
                }
            }
        }
        return nodeNum > 0 ? rand.nextInt(nodeNum) : 0;
    }

    private static List<Integer> indicesOf(List<Event> events,
            Class<? extends Event> type) {
        List<Integer> out = new ArrayList<>();
        if (events == null) {
            return out;
        }
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                out.add(i);
            }
        }
        return out;
    }

    private static int firstIndexOf(List<Event> events,
            Class<? extends Event> type) {
        if (events == null) {
            return -1;
        }
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isUpgradeLifecycleEvent(Event e) {
        return e instanceof UpgradeOp
                || e instanceof FinalizeUpgrade
                || e instanceof PrepareUpgrade;
    }
}
