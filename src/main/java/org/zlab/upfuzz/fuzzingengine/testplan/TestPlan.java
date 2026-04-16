package org.zlab.upfuzz.fuzzingengine.testplan;

import java.io.Serializable;
import java.util.*;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.upfuzz.Command;
import org.zlab.upfuzz.CommandPool;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.server.Seed;
import org.zlab.upfuzz.fuzzingengine.testplan.event.Event;
import org.zlab.upfuzz.fuzzingengine.testplan.event.command.ShellCommand;
import org.zlab.upfuzz.fuzzingengine.testplan.event.fault.Fault;
import org.zlab.upfuzz.fuzzingengine.testplan.event.fault.FaultRecover;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.UpgradeOp;
import org.zlab.upfuzz.utils.Pair;
import org.zlab.upfuzz.utils.Utilities;

import static org.zlab.upfuzz.utils.Utilities.rand;

public class TestPlan implements Serializable {
    static Logger logger = LogManager.getLogger(TestPlan.class);

    public int nodeNum;
    public List<Event> events;

    public Seed seed; // Make this transient?

    // Phase 0 observability: testPacketID captured when this plan was last
    // admitted to the corpus. -1 means the plan has never been saved yet and
    // any mutations originating from it cannot be credited to a lifecycle
    // record.
    public int lineageTestId = -1;

    // ----read results comparison----
    public List<String> validationCommands;
    public List<String> validationReadResultsOracle;

    public TestPlan(int nodeNum, List<Event> events,
            List<String> validationCommands,
            List<String> validationReadResultsOracle) {
        this(nodeNum, events, null,
                validationCommands, validationReadResultsOracle);
    }

    public TestPlan(int nodeNum, List<Event> events, Seed seed,
            List<String> validationCommands,
            List<String> validationReadResultsOracle) {

        assert validationCommands != null;
        assert validationReadResultsOracle != null;

        this.nodeNum = nodeNum;
        this.events = events;
        this.seed = seed;
        this.validationCommands = validationCommands;
        this.validationReadResultsOracle = validationReadResultsOracle;
    }

    public List<Event> getEvents() {
        return events;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("test plan:\n");
        for (Event event : events) {
            sb.append(event).append("\n");
        }
        sb.append("test plan end\n");

        sb.append("validation commands: \n");
        for (String cmd : validationCommands) {
            sb.append(cmd).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    public boolean mutate(CommandPool commandPool,
            Class<? extends State> stateClass) {
        List<Integer> faultIdxes = getIdxes(events, Fault.class);
        List<Integer> faultRecoverIdxes = getIdxes(events, FaultRecover.class);
        List<Integer> upgradeOpIdxes = getIdxes(events, UpgradeOp.class);
        List<Integer> shellCommandIdxes = getIdxes(events, ShellCommand.class);

        int mutateType = rand.nextInt(8);

        logger.debug("[hklog] testplan mutate type: " + mutateType);
        if (mutateType == 0) {
            // Inject a fault
            Pair<Fault, FaultRecover> faultPair = Fault
                    .randomGenerateFault(Config.getConf().nodeNum);
            // Pick a position and inject it
            int pos1 = rand.nextInt(events.size() + 1);
            assert faultPair != null;
            events.add(pos1, faultPair.left);
            if (faultPair.right != null) {
                int pos2 = Utilities.randWithRange(pos1 + 1,
                        events.size() + 1);
                events.add(pos2, faultPair.right);
            }
            return true;
        } else if (mutateType == 1) {
            // Remove a fault
            if (faultIdxes.isEmpty())
                return false;
            int pos = rand.nextInt(faultIdxes.size());
            events.remove((int) faultIdxes.get(pos));
            return true;
        } else if (mutateType == 2) {
            // Inject a fault recover
            if (faultIdxes.isEmpty())
                return false;
            int pos1 = rand.nextInt(faultIdxes.size());
            Fault fault = (Fault) events.get(faultIdxes.get(pos1));
            FaultRecover faultRecover = fault.generateRecover();
            if (faultRecover == null)
                return false;
            int pos2 = Utilities.randWithRange(faultIdxes.get(pos1),
                    events.size() + 1);
            events.add(pos2, faultRecover);
            return true;
        } else if (mutateType == 3) {
            // Remove a fault recover
            if (faultRecoverIdxes.isEmpty())
                return false;
            int pos = rand.nextInt(faultRecoverIdxes.size());
            events.remove((int) faultRecoverIdxes.get(pos));
            return true;
        } else if (mutateType == 4) {
            if (seed == null || seed.originalCommandSequence == null)
                throw new RuntimeException(
                        "Seed is null, cannot mutate command sequence");

            assert shellCommandIdxes.size() == seed.originalCommandSequence
                    .getSize();
            // Set index for events
            for (int i = 0; i < events.size(); i++)
                events.get(i).index = i;
            // Set Index for commands
            for (int i = 0; i < seed.originalCommandSequence.getSize(); i++) {
                ShellCommand cmdEvent = (ShellCommand) events
                        .get(shellCommandIdxes.get(i));
                Command cmd = seed.originalCommandSequence.commands.get(i);
                cmd.index = cmdEvent.index;
                cmd.nodeIndex = cmdEvent.getNodeIndex();
            }

            // Clone a new seed
            Seed mutateSeed = SerializationUtils.clone(seed);

            // Mutate it
            int mutationFailCount = 0;
            while (mutationFailCount < Config.getConf().mutationFailLimit) {
                if (mutateSeed.mutate(commandPool, stateClass))
                    break;
                logger.debug("Mutation failed");
                mutationFailCount++;
            }

            if (mutationFailCount == Config.getConf().mutationFailLimit) {
                logger.debug("Mutation failed, skip this mutation");
                return false;
            }

            // Re-interleave the commands and fault/upgrade

            // Gather the original upgrade/faults
            List<Event> upgradeAndFaults = new LinkedList<>();
            for (Event value : events) {
                if (!(value instanceof ShellCommand)) {
                    upgradeAndFaults.add(value);
                }
            }

            // Keep the original location
            List<Event> mergedEvents = new LinkedList<>();

            int i = 0; // fault/upgrade
            int j = 0; // command

            while (i < upgradeAndFaults.size()
                    && j < mutateSeed.originalCommandSequence.getSize()) {
                Event upgradeOrFault = upgradeAndFaults.get(i);
                Command command = mutateSeed.originalCommandSequence.commands
                        .get(j);

                // Condition 1: Old command
                if (command.index != -1) {
                    // compare and add
                    if (upgradeOrFault.index < command.index) {
                        mergedEvents.add(upgradeOrFault);
                        i++;
                    } else {
                        int nodeIndex = command.nodeIndex;
                        assert nodeIndex != -1;
                        mergedEvents.add(new ShellCommand(
                                command.constructCommandString(), nodeIndex));
                        j++;
                    }
                    continue;
                }

                // Condition 2: New Command
                int closestUpgradeAndFaultIndex = upgradeAndFaults.get(i).index;
                int closestCommandIndex = -1;
                int jj = j;
                while (jj < mutateSeed.originalCommandSequence.getSize()) {
                    if (mutateSeed.originalCommandSequence.commands
                            .get(jj).index != -1) {
                        closestCommandIndex = mutateSeed.originalCommandSequence.commands
                                .get(jj).index;
                        break;
                    }
                    jj++;
                }

                // No more ordering needed || upgrade/fault first
                if (closestCommandIndex == -1
                        || closestUpgradeAndFaultIndex < closestCommandIndex) {
                    // Random pick one and continue the iteration
                    // TODO: decide whether we use skew prob model
                    if (rand.nextBoolean()) {
                        mergedEvents.add(upgradeOrFault);
                        i++;
                    } else {
                        int nodeIndex = rand.nextInt(nodeNum);
                        mergedEvents.add(new ShellCommand(
                                command.constructCommandString(), nodeIndex));
                        j++;
                    }
                } else {
                    int nodeIndex = rand.nextInt(nodeNum);
                    mergedEvents.add(new ShellCommand(
                            command.constructCommandString(), nodeIndex));
                    j++;
                }
            }

            // Add rest commands
            while (j < mutateSeed.originalCommandSequence.getSize()) {
                Command command = mutateSeed.originalCommandSequence.commands
                        .get(j);
                int nodeIndex = command.nodeIndex;
                if (nodeIndex == -1)
                    nodeIndex = rand.nextInt(nodeNum);
                mergedEvents.add(new ShellCommand(
                        command.constructCommandString(), nodeIndex));
                j++;
            }

            // Add the rest of the upgrade/faults
            while (i < upgradeAndFaults.size()) {
                Event upgradeOrFault = upgradeAndFaults.get(i);
                mergedEvents.add(upgradeOrFault);
                i++;
            }

            // Step3: replace events, seed, and validation state atomically
            events = mergedEvents;
            this.seed = mutateSeed;
            this.validationCommands = mutateSeed.validationCommandSequence
                    .getCommandStringList();
            // Oracle is stale after mutation; will be re-collected on next
            // execution
            this.validationReadResultsOracle = new LinkedList<>();
            return true;
        } else if (mutateType == 5) {
            // Mutate event execution interval
            int pos = rand.nextInt(events.size());
            Event event = events.get(pos);
            // mutate the time interval
            int interval = event.interval;
            // try a few times
            for (int count = 0; count < 10; count++) {
                int newInterval = Utilities.randWithRange(
                        Config.getConf().intervalMin,
                        Config.getConf().intervalMax);
                if (newInterval != interval) {
                    event.interval = newInterval;
                    return true;
                }
            }
            return false;
        } else if (mutateType == 6) {
            // Mutate node idx of a shell command
            int idx = rand.nextInt(shellCommandIdxes.size());
            Event event = events.get(shellCommandIdxes.get(idx));
            assert event instanceof ShellCommand;
            ShellCommand shellCommand = (ShellCommand) event;

            for (int count = 0; count < 10; count++) {
                int nodeIndex = rand.nextInt(nodeNum);
                if (nodeIndex != shellCommand.getNodeIndex()) {
                    shellCommand.setNodeIndex(nodeIndex);
                    return true;
                }
            }
            return false;
        } else if (mutateType == 7) {
            logger.debug("Mutate Upgrade Order");

            if (!Config.getConf().shuffleUpgradeOrder || nodeNum <= 1)
                return false;

            if (upgradeOpIdxes.size() < 2)
                return false;

            Collections.shuffle(upgradeOpIdxes);
            int pos1 = upgradeOpIdxes.get(0);
            int pos2 = upgradeOpIdxes.get(1);
            int nodeIndex = ((UpgradeOp) events.get(pos1)).nodeIndex;
            ((UpgradeOp) events.get(pos1)).nodeIndex = ((UpgradeOp) events
                    .get(pos2)).nodeIndex;
            ((UpgradeOp) events.get(pos2)).nodeIndex = nodeIndex;

            return true;
        }
        throw new RuntimeException(
                "mutateType[%d] is out of range");
    }

    public List<Integer> getIdxes(List<Event> events,
            Class<? extends Event> clazz) {
        List<Integer> idxes = new LinkedList<>();
        for (int i = 0; i < events.size(); i++) {
            if (clazz.isInstance(events.get(i)))
                idxes.add(i);
        }
        return idxes;
    }

    /**
     * Phase 3 short-term dedup helper. Returns a compact, stable string
     * that captures the structural skeleton of this plan:
     *
     * <ul>
     *   <li>event class name plus the event's
     *       {@link Event#compactSignatureFragment()} — overridden in
     *       {@link UpgradeOp}, {@link ShellCommand}, and the full
     *       fault family so multi-node fault structure
     *       (LinkFailure n1/n2, PartitionFailure node sets) lands in
     *       the signature instead of collapsing through a bare class
     *       name,</li>
     *   <li>the upgrade order (ordered list of upgraded node indices),</li>
     *   <li>the validation command list.</li>
     * </ul>
     *
     * <p>This method is <em>lineage-agnostic</em> — two plans with the
     * same skeleton but different lineage roots still collide here.
     * For Phase 3 short-term dedup, use
     * {@link #compactSignature(int)} which mixes in the lineage root
     * so independent parents are not merged.
     */
    public String compactSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append("events=[");
        if (events != null) {
            for (Event event : events) {
                appendEventSkeleton(sb, event);
                sb.append('|');
            }
        }
        sb.append("];upgradeOrder=[");
        if (events != null) {
            for (Event event : events) {
                if (event instanceof UpgradeOp) {
                    sb.append(((UpgradeOp) event).nodeIndex).append(',');
                }
            }
        }
        sb.append("];validation=[");
        if (validationCommands != null) {
            for (String cmd : validationCommands) {
                if (cmd == null) {
                    sb.append("\0");
                } else {
                    sb.append(cmd);
                }
                sb.append('|');
            }
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Phase 3 short-term dedup key. Identical to
     * {@link #compactSignature()} but prefixed with the given lineage
     * root, so two plans from independent parents never collapse even
     * when their structural skeletons match. Pass {@code -1} (or any
     * negative value) when no lineage root is known — this produces
     * a unique key per call so the admission is never dedup-collapsed
     * against an earlier entry.
     */
    public String compactSignature(int lineageRoot) {
        if (lineageRoot < 0) {
            // Sentinel lineage — never collapse with any other entry.
            return "lineage=anon-" + System.identityHashCode(this) + ";"
                    + compactSignature();
        }
        return "lineage=" + lineageRoot + ";" + compactSignature();
    }

    private static void appendEventSkeleton(StringBuilder sb, Event event) {
        if (event == null) {
            sb.append("null");
            return;
        }
        sb.append(event.getClass().getSimpleName());
        String fragment = event.compactSignatureFragment();
        if (fragment != null && !fragment.isEmpty()) {
            sb.append('@').append(fragment);
        }
    }

    public void print() {
        System.out.println("Test Plan:");
        for (Event event : events) {
            System.out.println(event);
        }
    }
}
