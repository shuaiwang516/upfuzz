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

            // Step3: replace the events
            events = mergedEvents;
            // No need to update the index, as it will always be reset
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

    public void print() {
        System.out.println("Test Plan:");
        for (Event event : events) {
            System.out.println(event);
        }
    }
}
