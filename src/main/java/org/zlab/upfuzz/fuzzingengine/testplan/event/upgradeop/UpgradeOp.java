package org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop;

import org.zlab.upfuzz.fuzzingengine.testplan.event.Event;

public class UpgradeOp extends Event {
    public int nodeIndex;

    public UpgradeOp(int nodeIndex) {
        super("UpgradeOp");
        this.nodeIndex = nodeIndex;
    }

    @Override
    public String compactSignatureFragment() {
        return "n=" + nodeIndex;
    }

    @Override
    public String toString() {
        return String.format("[UpgradeOp] Upgrade Node[%d]", nodeIndex);
    }
}
