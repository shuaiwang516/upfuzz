package org.zlab.upfuzz.fuzzingengine.testplan.event.command;

import org.zlab.upfuzz.fuzzingengine.server.Seed;
import org.zlab.upfuzz.fuzzingengine.testplan.event.Event;

import java.util.LinkedList;
import java.util.List;

/**
 * commands which are executed in the shell of
 * system. Like cqlsh shell, hbase shell.
 */
public class ShellCommand extends Event {
    int nodeIndex = 0; // Default node ID
    String command;

    public ShellCommand(String command) {
        super("ShellCommand");
        this.command = command;
    }

    public ShellCommand(String command, int nodeIndex) {
        super("ShellCommand");
        this.command = command;
        this.nodeIndex = nodeIndex;
    }

    public static List<Event> seedWriteCmd2Events(Seed seed) {
        if (seed == null)
            return null;
        List<Event> events = new LinkedList<>();
        for (String command : seed.originalCommandSequence
                .getCommandStringList()) {
            events.add(new ShellCommand(command));
        }
        return events;
    }

    public static List<Event> seedWriteCmd2Events(Seed seed, int nodeNum) {
        if (seed == null)
            return null;
        List<Event> events = new LinkedList<>();
        for (String command : seed.originalCommandSequence
                .getCommandStringList()) {
            int nodeIndex = (int) (Math.random() * nodeNum);
            events.add(new ShellCommand(command, nodeIndex));
        }
        return events;
    }

    public static List<Event> seedValidationCmd2Events(Seed seed) {
        if (seed == null)
            return null;
        List<Event> events = new LinkedList<>();
        for (String command : seed.validationCommandSequence
                .getCommandStringList()) {
            events.add(new ShellCommand(command));
        }
        return events;
    }

    public String getCommand() {
        return command;
    }

    public int getNodeIndex() {
        return nodeIndex;
    }

    public void setNodeIndex(int nodeIndex) {
        this.nodeIndex = nodeIndex;
    }

    @Override
    public String toString() {
        return String.format("[Command][Node[%d]] Execute {%s}", nodeIndex,
                command);
    }
}
