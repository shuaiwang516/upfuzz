package org.zlab.upfuzz.utils;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.junit.jupiter.api.Test;
import org.zlab.upfuzz.docker.DockerMeta;

import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UtilitiesTest {

    @Test
    public void testMaskRubyObject() {

        String input = "Old Version Result: HOST  REGION\n" +
                " hregion2:16020 {ENCODED => 7a529323a5cf21e55f89208b99d6cc15, NAME => 'uuid2217c5c8ac544928912ffe83069309ea,,1694980141812.7a529323a5cf21e55f89208b99d6cc15.', STARTKEY => '', ENDKEY => ''}\n"
                +
                "1 row(s)\n" +
                "Took 1.6793 seconds\n" +
                "=> #<Java::OrgApacheHadoopHbase::HRegionLocation:0x7305191e>";
        String output = Utilities.maskRubyObject(input);
        System.out.println(output);
    }

    @Test
    public void testSetRandomDeleteAtLeaseOneItem() {
        Set<String> set = new HashSet<>();
        set.add("a");
        set.add("b");
        set.add("c");
        set.add("d");
        Boolean status = Utilities.setRandomDeleteAtLeaseOneItem(set);
        System.out.println(set);
        System.out.println(status);
    }

    @Test
    public void testExponentialProbabilityModel() {
        Utilities.ExponentialProbabilityModel model = new Utilities.ExponentialProbabilityModel(
                0.4, 0.1, 5);
        assert model.calculateProbability(0) == 0.4;
        System.out.println(model.calculateProbability(10));
    }

    @Test
    public void testComputeMF() {
        Map<String, Map<String, String>> oriClassInfo = new HashMap<>();
        Map<String, Map<String, String>> upClassInfo = new HashMap<>();
        oriClassInfo.put("A", new HashMap<>());
        oriClassInfo.put("B", new HashMap<>());
        oriClassInfo.put("C", new HashMap<>());

        oriClassInfo.get("A").put("f1", "int");
        oriClassInfo.get("B").put("f1", "String");
        oriClassInfo.get("C").put("f2", "List");

        upClassInfo.put("A", new HashMap<>());
        upClassInfo.put("B", new HashMap<>());
        upClassInfo.put("D", new HashMap<>());

        upClassInfo.get("A").put("f1", "int");
        upClassInfo.get("B").put("f1", "String");
        upClassInfo.get("D").put("f2", "List");

        Map<String, Map<String, String>> mf = Utilities.computeMF(oriClassInfo,
                upClassInfo);
        assert mf.containsKey("A");
        assert mf.containsKey("B");
        assert mf.get("B").containsKey("f1");
    }

    @Test
    public void testIsBlackListed() {
        String errorLog = "        at " +
                "org.apache.cassandra.db.composites.CompoundSparseCellNameType.create"
                +
                "(CompoundSparseCellNameType.java:126) " +
                "~[apache-cassandra-2.2.19-SNAPSHOT.jar:2.2.19-SNAPSHOT]";
        Set<String> blackListErrorLog = new HashSet<>();
        blackListErrorLog.add(
                "org.apache.cassandra.db.composites.CompoundSparseCellNameType.create"
                        +
                        "(CompoundSparseCellNameType.java:126)" +
                        " ~[apache-cassandra-2.2.19-SNAPSHOT.jar:2.2.19-SNAPSHOT]");

        assert DockerMeta.isBlackListed(errorLog, blackListErrorLog);
    }

    @Test
    public void testComputeChangedClasses() {
        Map<String, Map<String, String>> oriClassInfo = new HashMap<>();
        Map<String, Map<String, String>> upClassInfo = new HashMap<>();
        oriClassInfo.put("A", new HashMap<>());
        oriClassInfo.put("B", new HashMap<>());
        oriClassInfo.put("C", new HashMap<>());
        oriClassInfo.put("E", new HashMap<>());
        oriClassInfo.put("F", new HashMap<>());

        oriClassInfo.get("A").put("f1", "int");
        oriClassInfo.get("B").put("f1", "String");
        oriClassInfo.get("C").put("f2", "List");
        oriClassInfo.get("E").put("f2", "List");
        oriClassInfo.get("F").put("f1", "int");
        oriClassInfo.get("F").put("f2", "String");

        upClassInfo.put("A", new HashMap<>());
        upClassInfo.put("B", new HashMap<>());
        upClassInfo.put("D", new HashMap<>());
        upClassInfo.put("E", new HashMap<>());
        upClassInfo.put("F", new HashMap<>());

        upClassInfo.get("A").put("f1", "int");
        upClassInfo.get("B").put("f1", "String");
        upClassInfo.get("D").put("f2", "List");
        upClassInfo.get("E").put("f2", "Array");
        upClassInfo.get("F").put("f1", "int");
        upClassInfo.get("F").put("f2", "String");
        upClassInfo.get("F").put("f3", "bool");

        Set<String> changedClasses = Utilities.computeChangedClasses(
                oriClassInfo,
                upClassInfo);
        assert changedClasses.contains("C");
        assert changedClasses.contains("E");
        assert changedClasses.contains("F");
        assert !changedClasses.contains("D");
    }

    /**
     * Phase 0 regression: two class ids that differ only in their high
     * bits must not collide in
     * {@link Utilities#collectNewProbeIds(ExecutionDataStore, ExecutionDataStore)}.
     * The pre-fix implementation encoded probes as
     * {@code (classId << 20) | probeIndex} which drops the top 20 bits
     * of the 64-bit JaCoCo class id, so distinct classes that differed
     * above bit 44 silently merged into one probe set. This test picks
     * two class ids which collide under that encoding and verifies
     * that the new API keeps them separate.
     */
    @Test
    public void testCollectNewProbeIdsNoHighBitCollision() {
        long classIdA = 0x0000_1234_5678_9ABCL;
        long classIdB = 0x1000_1234_5678_9ABCL; // differs only above bit 44
        // Sanity check the old lossy encoding collides.
        assertEquals(classIdA << 20, classIdB << 20);

        ExecutionDataStore store = new ExecutionDataStore();
        ExecutionData probesA = new ExecutionData(classIdA, "ClassA",
                new int[] { 1, 0, 1, 0 });
        ExecutionData probesB = new ExecutionData(classIdB, "ClassB",
                new int[] { 0, 1, 0, 0 });
        store.put(probesA);
        store.put(probesB);

        Map<Long, BitSet> newProbes = Utilities.collectNewProbeIds(null,
                store);

        assertTrue(newProbes.containsKey(classIdA));
        assertTrue(newProbes.containsKey(classIdB));
        assertEquals(2, newProbes.size());

        BitSet bitsA = newProbes.get(classIdA);
        BitSet bitsB = newProbes.get(classIdB);
        assertTrue(bitsA.get(0));
        assertFalse(bitsA.get(1));
        assertTrue(bitsA.get(2));
        assertFalse(bitsB.get(0));
        assertTrue(bitsB.get(1));
        assertFalse(bitsB.get(2));

        assertEquals(3, Utilities.countProbes(newProbes));
        // No probe index overlaps between the two classes.
        assertEquals(0,
                Utilities.intersectProbeCount(
                        Collections.singletonMap(classIdA, bitsA),
                        Collections.singletonMap(classIdB, bitsB)));
    }

    /**
     * Phase 0 regression: {@link Utilities#intersectProbeCount} counts
     * only probes that are set in both maps for the same class id.
     * This guards against future optimizations from accidentally
     * bucketing two classes with the same {@code classId.hashCode()}
     * together.
     */
    @Test
    public void testIntersectProbeCount() {
        long classA = 42L;
        long classB = 99L;

        BitSet a0 = new BitSet();
        a0.set(1);
        a0.set(3);
        a0.set(5);

        BitSet a1 = new BitSet();
        a1.set(1);
        a1.set(2);
        a1.set(5);

        BitSet b = new BitSet();
        b.set(3);

        Map<Long, BitSet> lhs = new HashMap<>();
        lhs.put(classA, a0);
        lhs.put(classB, b);

        Map<Long, BitSet> rhs = new HashMap<>();
        rhs.put(classA, a1);
        // classB intentionally missing on rhs.

        // classA: shared = {1, 5} -> 2 probes. classB: no overlap -> 0.
        assertEquals(2, Utilities.intersectProbeCount(lhs, rhs));

        // Order-independence.
        assertEquals(2, Utilities.intersectProbeCount(rhs, lhs));

        // Empty maps.
        assertEquals(0,
                Utilities.intersectProbeCount(new HashMap<>(), rhs));
        assertEquals(0, Utilities.intersectProbeCount(lhs, null));
    }
}
