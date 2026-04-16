package org.zlab.upfuzz.fuzzingengine.testplan.event.fault;

import java.util.Set;
import java.util.TreeSet;

public class PartitionFailureRecover extends FaultRecover {
    public Set<Integer> nodeSet1;
    public Set<Integer> nodeSet2;

    public PartitionFailureRecover(Set<Integer> nodeSet1,
            Set<Integer> nodeSet2) {
        super("PartitionFailureRecover");
        this.nodeSet1 = nodeSet1;
        this.nodeSet2 = nodeSet2;
    }

    @Override
    public String compactSignatureFragment() {
        String a = nodeSet1 == null
                ? "[]"
                : new TreeSet<>(nodeSet1).toString();
        String b = nodeSet2 == null
                ? "[]"
                : new TreeSet<>(nodeSet2).toString();
        if (a.compareTo(b) > 0) {
            String tmp = a;
            a = b;
            b = tmp;
        }
        return "p1=" + a + ",p2=" + b;
    }
}
