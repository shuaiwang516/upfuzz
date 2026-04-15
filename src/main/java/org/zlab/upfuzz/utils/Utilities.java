package org.zlab.upfuzz.utils;

import static java.lang.String.format;

import java.io.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.zlab.upfuzz.Command;
import org.zlab.upfuzz.Parameter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.zlab.upfuzz.CommandSequence;
import org.zlab.upfuzz.ParameterType;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.FeedBack;
import org.zlab.upfuzz.fuzzingengine.packet.StackedTestPacket;
import org.zlab.upfuzz.fuzzingengine.packet.TestPacket;

public class Utilities {
    static Logger logger = LogManager.getLogger(Utilities.class);

    // TODO: control the seed
    public static final SplittableRandom rand = new SplittableRandom();

    public static List<Integer> permutation(int size) {
        List<Integer> indexArray = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            indexArray.add(i);
        }
        Collections.shuffle(indexArray);
        return indexArray;
    }

    public static Pair<CommandSequence, CommandSequence> deserializeCommandSequence(
            Path filePath) {
        Pair<CommandSequence, CommandSequence> commandSequencePair = null;
        try {
            FileInputStream fileIn = new FileInputStream(filePath.toFile());
            ObjectInputStream in = new ObjectInputStream(fileIn);
            commandSequencePair = (Pair<CommandSequence, CommandSequence>) in
                    .readObject();
            in.close();
            fileIn.close();
        } catch (IOException i) {
            i.printStackTrace();
            return null;
        } catch (ClassNotFoundException c) {
            System.out.println("Command class not found");
            c.printStackTrace();
            return null;
        }
        return commandSequencePair;
    }

    public static boolean isEqualCoverage(ExecutionDataStore curCoverage,
            ExecutionDataStore testSequenceCoverage) {
        // Return true if two coverage is identical
        if (testSequenceCoverage == null && curCoverage != null)
            return false;

        if (testSequenceCoverage != null && curCoverage == null)
            return false;

        for (final ExecutionData testSequenceData : testSequenceCoverage
                .getContents()) {

            final Long id = Long.valueOf(testSequenceData.getId());
            final ExecutionData curData = curCoverage.get(id);

            // For one class, merge the coverage
            if (curData != null) {
                assertCompatibility(curData, testSequenceData);
                int[] curProbes = curData.getProbes();
                final int[] testSequenceProbes = testSequenceData.getProbes();
                for (int i = 0; i < curProbes.length; i++) {
                    // Now only try with the boolean first
                    if ((curProbes[i] == 0 && testSequenceProbes[i] != 0) ||
                            (curProbes[i] != 0 && testSequenceProbes[i] == 0)) {
                        // logger.debug("cur probes: ");
                        // for (int j = 0; j < curProbes.length; j++) {
                        // logger.debug(curProbes[j] + " ");
                        // }
                        // logger.debug("test probes: ");
                        // for (int j = 0; j < testSequenceProbes.length; j++) {
                        // logger.debug(testSequenceProbes[j] + " ");
                        // }

                        // logger.debug("probe len = " + curProbes.length);
                        // logger.debug("Class " + testSequenceData.getName() +
                        // " id: [" + i + "]"
                        // + " is different!");
                        return false;
                    }
                }
            } else {
                logger.debug("curData not triggered " +
                        testSequenceData.getName());
                return false;
            }
        }
        return true;
    }

    public static void printCoverages(int testId, FeedBack[] feedBacks,
            String type) {
        FeedBack fb = new FeedBack();
        int i = 0;
        for (FeedBack feedBack : feedBacks) {
            for (ExecutionData curData : feedBack.originalCodeCoverage
                    .getContents()) {
                final Long id = Long.valueOf(curData.getId());
                final String name = curData.getName();
                int[] curProbes = curData.getProbes();
                System.out.println();
                logger.info(
                        "printing coverages for testId: " + testId + ", " +
                                "feedback probe " + id + ", class " + name
                                + " from " + type + ": ");
                String probeDetails = "";
                for (int j = 0; j < curProbes.length; j++) {
                    probeDetails += (curProbes[j] + " ");
                }
                logger.info(probeDetails);
                i += 1;
            }
        }
    }

    public static boolean hasNewBits(ExecutionDataStore curCoverage,
            ExecutionDataStore testSequenceCoverage) {
        if (Config.getConf().enableHitCount) {
            return hasNewBitsAccum(curCoverage, testSequenceCoverage);
        } else {
            if (Config.getConf().debugCoverage) {
                return hasNewBitsDebug(curCoverage, testSequenceCoverage);
            } else {
                return hasNewBitsBoolean(curCoverage, testSequenceCoverage);
            }
        }
    }

    private static boolean hasNewBitsBoolean(ExecutionDataStore curCoverage,
            ExecutionDataStore testSequenceCoverage) {

        if (testSequenceCoverage == null)
            return false;

        if (curCoverage == null) {
            return true;
        } else {
            for (final ExecutionData testSequenceData : testSequenceCoverage
                    .getContents()) {

                final long id = testSequenceData.getId();
                final ExecutionData curData = curCoverage.get(id);

                // For one class, merge the coverage
                if (curData != null) {
                    assertCompatibility(curData, testSequenceData);
                    int[] curProbes = curData.getProbes();
                    final int[] testSequenceProbes = testSequenceData
                            .getProbes();
                    for (int i = 0; i < curProbes.length; i++) {
                        // Now only try with the boolean first
                        if (curProbes[i] == 0 && testSequenceProbes[i] != 0) {
                            // System.out.println();
                            // System.out.print("cur probes: ");
                            // for (int j = 0; j < curProbes.length; j++) {
                            // System.out.print(curProbes[j] + " ");
                            // }
                            // System.out.println();
                            // System.out.print("test probes: ");
                            // for (int j = 0; j < testSequenceProbes.length;
                            // j++) {
                            // System.out.print(testSequenceProbes[j] + " ");
                            // }
                            // System.out.println();

                            // System.out
                            // .println("probe len = " + curProbes.length);
                            // System.out.println("Class "
                            // + testSequenceData.getName() + " id: [" + i
                            // + "]" + " is different!");
                            return true;
                        }
                    }
                } else {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean hasNewBitsDebug(ExecutionDataStore curCoverage,
            ExecutionDataStore testSequenceCoverage) {

        if (testSequenceCoverage == null)
            return false;

        if (curCoverage == null) {
            return true;
        } else {
            boolean newBit = false;
            for (final ExecutionData testSequenceData : testSequenceCoverage
                    .getContents()) {

                final long id = testSequenceData.getId();
                final ExecutionData curData = curCoverage.get(id);

                // For one class, merge the coverage
                if (curData != null) {
                    assertCompatibility(curData, testSequenceData);
                    int[] curProbes = curData.getProbes();
                    final int[] testSequenceProbes = testSequenceData
                            .getProbes();
                    for (int i = 0; i < curProbes.length; i++) {
                        // Now only try with the boolean first
                        if (curProbes[i] == 0 && testSequenceProbes[i] != 0) {
                            logger.debug("[Coverage] class "
                                    + testSequenceData.getName()
                                    + " has a new coverage");
                            // System.out.println();
                            // System.out.print("cur probes: ");
                            // for (int j = 0; j < curProbes.length; j++) {
                            // System.out.print(curProbes[j] + " ");
                            // }
                            // System.out.println();
                            // System.out.print("test probes: ");
                            // for (int j = 0; j < testSequenceProbes.length;
                            // j++) {
                            // System.out.print(testSequenceProbes[j] + " ");
                            // }
                            // System.out.println();

                            // System.out.println("probe len = " +
                            // curProbes.length);
                            // System.out.println("Class " +
                            // testSequenceData.getName() +
                            // " id: [" + i + "]"
                            // + " is different!");
                            newBit = true;
                            break;
                        }
                    }
                } else {
                    logger.debug(
                            "[Coverage] class " + testSequenceData.getName()
                                    + " has new coverage (new class)");
                    newBit = true;
                }
            }
            return newBit;
        }
    }

    public static void writeObjectToFile(File file, Object obj)
            throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        ObjectOutputStream oos = new ObjectOutputStream(fos);
        logger.info("Got serializable object? " + obj instanceof Serializable);
        oos.writeObject(obj);
        logger.info(true);
        oos.close();
        fos.close();
    }

    public static Object readObjectFromFile(File file)
            throws IOException, ClassNotFoundException {
        FileInputStream fis = new FileInputStream(file);
        ObjectInputStream ois = new ObjectInputStream(fis);
        Object result = ois.readObject();
        ois.close();
        fis.close();
        return result;
    }

    public static boolean hasNewBitsAccum(ExecutionDataStore curCoverage,
            ExecutionDataStore testSequenceCoverage) {

        if (testSequenceCoverage == null)
            return false;

        if (curCoverage == null) {
            return true;
        } else {
            for (final ExecutionData testSequenceData : testSequenceCoverage
                    .getContents()) {
                final long id = testSequenceData.getId();
                final ExecutionData curData = curCoverage.get(id);

                // For one class, merge the coverage
                if (curData != null) {
                    assertCompatibility(curData, testSequenceData);
                    int[] curProbes = curData.getProbes();
                    final int[] testSequenceProbes = testSequenceData
                            .getProbes();
                    for (int i = 0; i < curProbes.length; i++) {
                        if (curProbes[i] < testSequenceProbes[i]
                                && getBucketIndex(
                                        curProbes[i]) < getBucketIndex(
                                                testSequenceProbes[i])) {
                            if (Config.getConf().debugHitCount) {
                                logger.debug("[Coverage] class "
                                        + testSequenceData.getName()
                                        + " has a new coverage" + " curProb = "
                                        + curProbes[i] + " curBucket = "
                                        + getBucketIndex(curProbes[i])
                                        + " testProb = " + testSequenceProbes[i]
                                        + " curBucket = " + getBucketIndex(
                                                testSequenceProbes[i]));
                            }
                            return true;
                        }
                    }
                } else {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Phase 0: collect the set of probes that are covered in
     * {@code testSequenceCoverage} but not yet in {@code curCoverage},
     * grouped by JaCoCo class id with the new probe indices as a
     * {@link BitSet}. JaCoCo class ids are 64-bit CRC-derived so any
     * truncation into a single {@code long} would collide between
     * unrelated classes — keeping the class id as the map key avoids
     * that. The BitSet values are cheap to intersect per-class in
     * {@link #intersectProbeCount(Map, Map)}.
     *
     * <p>Treats the same "new bit" predicate the boolean
     * {@link #hasNewBitsBoolean(ExecutionDataStore, ExecutionDataStore)}
     * variant uses: a probe is new when the baseline is zero and the
     * candidate is non-zero. The accumulating hit-count variant is
     * intentionally not re-used here because Phase 0 novelty
     * attribution only needs class-level boolean novelty to reason
     * about baseline-vs-rolling lane sources.
     */
    public static Map<Long, BitSet> collectNewProbeIds(
            ExecutionDataStore curCoverage,
            ExecutionDataStore testSequenceCoverage) {
        Map<Long, BitSet> newProbes = new HashMap<>();
        if (testSequenceCoverage == null) {
            return newProbes;
        }
        for (final ExecutionData testSequenceData : testSequenceCoverage
                .getContents()) {
            final long classId = testSequenceData.getId();
            final int[] testSequenceProbes = testSequenceData.getProbes();
            BitSet classBits = new BitSet(testSequenceProbes.length);
            final ExecutionData curData = curCoverage == null ? null
                    : curCoverage.get(classId);
            if (curData == null) {
                for (int i = 0; i < testSequenceProbes.length; i++) {
                    if (testSequenceProbes[i] != 0) {
                        classBits.set(i);
                    }
                }
            } else {
                int[] curProbes = curData.getProbes();
                int shared = Math.min(curProbes.length,
                        testSequenceProbes.length);
                for (int i = 0; i < shared; i++) {
                    if (curProbes[i] == 0 && testSequenceProbes[i] != 0) {
                        classBits.set(i);
                    }
                }
                // Extra probes past the baseline length are always "new".
                for (int i = shared; i < testSequenceProbes.length; i++) {
                    if (testSequenceProbes[i] != 0) {
                        classBits.set(i);
                    }
                }
            }
            if (!classBits.isEmpty()) {
                newProbes.put(classId, classBits);
            }
        }
        return newProbes;
    }

    /**
     * Total number of new probes across all classes in a map returned
     * by {@link #collectNewProbeIds}.
     */
    public static int countProbes(Map<Long, BitSet> probes) {
        if (probes == null || probes.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (BitSet bs : probes.values()) {
            total += bs.cardinality();
        }
        return total;
    }

    /**
     * Count probes that are present in both {@code a} and {@code b}.
     * Per-class intersection via {@link BitSet#and(BitSet)} so the
     * result is collision-free even for JaCoCo class ids that use the
     * full 64-bit range.
     */
    public static int intersectProbeCount(Map<Long, BitSet> a,
            Map<Long, BitSet> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Map<Long, BitSet> smaller = a.size() <= b.size() ? a : b;
        Map<Long, BitSet> larger = smaller == a ? b : a;
        int count = 0;
        for (Map.Entry<Long, BitSet> entry : smaller.entrySet()) {
            BitSet otherBits = larger.get(entry.getKey());
            if (otherBits == null) {
                continue;
            }
            BitSet tmp = (BitSet) entry.getValue().clone();
            tmp.and(otherBits);
            count += tmp.cardinality();
        }
        return count;
    }

    public static int mergeMax(ExecutionDataStore curCoverage,
            ExecutionDataStore testSequenceCoverage) {
        int score = 0;

        if (testSequenceCoverage == null)
            return score;

        if (curCoverage == null) {
            throw new RuntimeException("Please initialize curCoverage");
        }

        for (final ExecutionData testSequenceData : testSequenceCoverage
                .getContents()) {

            final long id = testSequenceData.getId();
            final ExecutionData curData = curCoverage.get(id);

            // For one class, merge the coverage
            if (curData != null) {
                assertCompatibility(curData, testSequenceData);
                int[] curProbes = curData.getProbes();
                final int[] testSequenceProbes = testSequenceData
                        .getProbes();
                for (int i = 0; i < curProbes.length; i++) {
                    if (curProbes[i] < testSequenceProbes[i]) {
                        score += testSequenceProbes[i] - curProbes[i];
                        curProbes[i] = testSequenceProbes[i];
                    }
                }
            } else {
                final int[] testSequenceProbes = testSequenceData
                        .getProbes();
                for (int i = 0; i < testSequenceProbes.length; i++) {
                    score += testSequenceProbes[i];
                }
            }
            curCoverage.merge(testSequenceData);
        }
        return score;
    }

    // Overwrite the merge function
    // use max instead of merge
    // compute the new bits bring by testSequenceCoverage
    public static boolean computeDelta(ExecutionDataStore curCoverage,
            ExecutionDataStore testSequenceCoverage) {

        System.out.println("Computing Delta");

        if (testSequenceCoverage == null)
            return false;

        if (curCoverage == null) {
            return true;
        } else {
            for (final ExecutionData testSequenceData : testSequenceCoverage
                    .getContents()) {

                boolean findNewBit = false;
                final Long id = Long.valueOf(testSequenceData.getId());
                final ExecutionData curData = curCoverage.get(id);

                // For one class, merge the coverage
                if (curData != null) {
                    assertCompatibility(curData, testSequenceData);
                    int[] curProbes = curData.getProbes();
                    final int[] testSequenceProbes = testSequenceData
                            .getProbes();
                    for (int i = 0; i < curProbes.length; i++) {
                        // Now only try with the boolean first
                        if (curProbes[i] == 0 && testSequenceProbes[i] != 0) {
                            continue;
                        } else {
                            findNewBit = true;
                            testSequenceProbes[i] = 0;
                        }
                    }
                    // if (findNewBit) {
                    // System.out.println(
                    // "new bit class: " + testSequenceData.getName());
                    // }
                } else {
                    // System.out.println(
                    // "new bit class: " + testSequenceData.getName());
                    continue;
                }
            }
            return false;
        }
    }

    private static int getBucketIndex(int exec_count) {
        if (exec_count == 1)
            return 0;
        if (exec_count == 2)
            return 1;
        if (exec_count == 3)
            return 2;
        if (exec_count >= 4 && exec_count <= 7)
            return 3;
        if (exec_count >= 8 && exec_count <= 15)
            return 4;
        if (exec_count >= 16 && exec_count <= 31)
            return 5;
        if (exec_count >= 32 && exec_count <= 127)
            return 6;
        return 7; // For 128+ executions
    }

    public static Pair<Integer, Integer> getCoverageStatus(
            ExecutionDataStore curCoverage) {
        if (curCoverage == null) {
            return new Pair(0, 0);
        }

        int coveredProbes = 0;
        int probeNum = 0;

        for (final ExecutionData curData : curCoverage.getContents()) {
            int[] curProbes = curData.getProbes();
            probeNum += curProbes.length;
            for (int i = 0; i < curProbes.length; i++) {
                if (curProbes[i] != 0)
                    coveredProbes++;
            }
        }
        return new Pair(coveredProbes, probeNum);
    }

    public static void assertCompatibility(ExecutionData curData,
            ExecutionData testSequenceData) {
        if (curData.getId() != testSequenceData.getId()) {
            throw new IllegalStateException(
                    format("Different ids (%016x and %016x).",
                            Long.valueOf(curData.getId()),
                            Long.valueOf(testSequenceData.getId())));
        }
        if (!curData.getName().equals(testSequenceData.getName())) {
            throw new IllegalStateException(
                    format("Different class names %s and %s for id %016x.",
                            curData.getName(), testSequenceData.getName(),
                            Long.valueOf(testSequenceData.getId())));
        }
        if (curData.getProbes().length != testSequenceData.getProbes().length) {
            throw new IllegalStateException(format(
                    "Incompatible execution data for class %s with id %016x.",
                    testSequenceData.getName(),
                    Long.valueOf(testSequenceData.getId())));
        }
    }

    public static Process runProcess(ProcessBuilder pb, String desc) {
        Process p = null;
        try {
            // System.out.println("Execute: " + desc);
            p = pb.start();
            // BufferedReader in = new BufferedReader(new
            // InputStreamReader(p.getInputStream()));
            // String line;
            // while ((line = in.readLine()) != null) {
            // System.out.println(line);
            // System.out.flush();
            // }
            p.waitFor();
            // in.close();
            // System.out.println(desc + " Successful");

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        return p;
    }

    public static Process exec(String[] cmds, File path) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmds).redirectErrorStream(true);
        pb.directory(path);
        return pb.start();
    }

    public static Process exec(String[] cmds, String path) throws IOException {
        return exec(cmds, new File(path));
    }

    public static String readProcess(Process p) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(p.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        try {
            while ((line = in.readLine()) != null) {
                sb.append(line);
            }
            p.waitFor();
            in.close();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        return sb.toString();
    }

    public static String readProcessErrorStream(Process p) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(p.getErrorStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        try {
            while ((line = in.readLine()) != null) {
                sb.append(line);
            }
            p.waitFor();
            in.close();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        return sb.toString();
    }

    public static String getGitBranch(String path)
            throws IOException, InterruptedException {
        Process p = exec(
                new String[] { "git", "rev-parse", "--abbrev-ref", "HEAD" },
                path);
        String gitBranch = readProcess(p).replace("\n", "");
        return gitBranch;
    }

    public static String getGitTag(String path)
            throws IOException, InterruptedException {
        Process p = exec(
                new String[] { "git", "describe", "--abbrev=0", "--tags",
                        "HEAD" },
                path);
        String gitBranch = readProcess(p).replace("\n", "");
        return gitBranch;
    }

    public static boolean oneOf(SplittableRandom rand, int n) {
        if (n <= 0) {
            throw new RuntimeException("n in oneOf <= 0");
        }
        return rand.nextInt(n) == 0;
    }

    public static boolean nOutOf(SplittableRandom rand, int x, int y) {
        // probability x/y
        if (y <= 0 || x < 0) {
            throw new RuntimeException("n in oneOf <= 0");
        }
        return rand.nextInt(y) < x;
    }

    public static List<Integer> pickKoutofN(int k, int n) {
        if (k > n || n <= 0)
            return null;
        List<Integer> indexes = new LinkedList<>();
        for (int i = 0; i < n; i++) {
            indexes.add(i);
        }
        if (k == n)
            return indexes;
        Collections.shuffle(indexes);
        List<Integer> retIndexes = new LinkedList<>();
        for (int i = 0; i < k; i++) {
            retIndexes.add(indexes.get(i));
        }
        return retIndexes;
    }

    public static boolean write2TXT(File file, String content, boolean append) {

        try {
            // If file doesn't exists, then create it
            if (!file.exists()) {
                file.createNewFile();
            }

            FileWriter fw = new FileWriter(file.getAbsoluteFile(), append);
            BufferedWriter bw = new BufferedWriter(fw);

            // Write in file
            bw.write(content);

            // Close connection
            bw.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return true;
    }

    public static boolean writeCmdSeq(File file, Object object) {
        try {
            FileOutputStream fileOut = new FileOutputStream(file);
            ObjectOutputStream out = new ObjectOutputStream(fileOut);
            out.writeObject(object);
            out.close();
            fileOut.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return true;
    }

    public static Map<String, List<Map.Entry<String, String>>> loadFunctoinFromStaticAnalysis(
            Path fileName) {
        JSONParser jsonParser = new JSONParser();
        Map<String, List<Map.Entry<String, String>>> funcToInst = new HashMap<>();

        try (FileReader reader = new FileReader(fileName.toFile())) {
            Object obj = jsonParser.parse(reader);
            JSONObject jsonObject = (JSONObject) obj;
            Set<Map.Entry<String, String>> entrySet = jsonObject.entrySet();

            for (Map.Entry<String, String> e : entrySet) {
                String[] ret = e.getValue()
                        .split(e.getKey().replace("$", "\\$") + "\\(");
                assert (ret.length == 2);
                String ClassName = ret[0].substring(0, ret[0].length() - 1);
                String MethodName = e.getKey();
                String ParamDesc = "(" + ret[1];

                // Only instrument class that's inside org.apache.cassandra.*
                if (ClassName.contains("cassandra")) {
                    if (funcToInst.containsKey(ClassName)) {
                        funcToInst.get(ClassName).add(
                                new AbstractMap.SimpleEntry<>(MethodName,
                                        ParamDesc));
                    } else {
                        List<Map.Entry<String, String>> list = new ArrayList<>();
                        list.add(new AbstractMap.SimpleEntry<>(MethodName,
                                ParamDesc));
                        funcToInst.put(ClassName, list);
                    }
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return funcToInst;
    }

    public void generateJacocoIncludeOption() {
        Path filePath = Paths.get("/Users/hanke/Desktop/SerDes.json");
        Map<String, List<Map.Entry<String, String>>> funcs = Utilities
                .loadFunctoinFromStaticAnalysis(filePath);
        for (String className : funcs.keySet()) {
            System.out.print(className + ":");
        }
        System.out.println();
    }

    // biasedRand returns a random int in range [0..n),
    // probability of n-1 is k times higher than probability of 0.
    public static int biasRand(SplittableRandom rand, int n, int k) {
        double nf = (float) n;
        double kf = (float) k;
        double rf = nf * (kf / 2 + 1) * rand.nextDouble();
        double bf = (-1 + Math.sqrt(1 + 2 * kf * rf / nf)) * nf / kf;
        return (int) bf;
    }

    public static int generateExponentialRandom(SplittableRandom rand,
            double lambda,
            int lowerBound, int upperBound) {
        double randomExponential = Math.log(1 - rand.nextDouble()) / (-lambda);
        // Scale and shift to fit the range [lowerBound, upperBound]
        int scaledValue = (int) (randomExponential + lowerBound);
        // Ensure it does not exceed the upperBound
        return Math.min(scaledValue, upperBound);
    }

    public static <T> T[] concatArray(T[] array1, T[] array2) {
        T[] result = Arrays.copyOf(array1, array1.length + array2.length);
        System.arraycopy(array2, 0, result, array1.length, array2.length);
        return result;
    }

    public static Set<Parameter> strings2Parameters(
            Collection<String> strings) {
        Set<Parameter> ret = new HashSet<>();
        for (String str : strings) {
            ret.add(new Parameter(CONSTANTSTRINGType.instance, str));
        }
        return ret;
    }

    public static ParameterType.InCollectionType createInStringCollectionType(
            Collection<String> strings) {
        return new ParameterType.InCollectionType(
                CONSTANTSTRINGType.instance,
                (s, c) -> Utilities
                        .strings2Parameters(
                                strings),
                null);
    }

    public static ParameterType.OptionalType createOptionalString(
            String s) {
        return new ParameterType.OptionalType(
                new CONSTANTSTRINGType(s), null);
    }

    public static Set<Parameter> strings2Parameters(
            String[] strings) {
        Set<Parameter> ret = new HashSet<>();
        for (String str : strings) {
            ret.add(new Parameter(CONSTANTSTRINGType.instance, str));
        }
        return ret;
    }

    public static Set<String> parameters2Strings(
            Collection<Parameter> parameters) {
        Set<String> ret = new HashSet<>();
        for (Parameter parameter : parameters) {
            assert parameter.getValue() instanceof String;
            ret.add((String) parameter.getValue());
        }
        return ret;
    }

    public static void saveSeed(CommandSequence commandSequence,
            CommandSequence validationCommandSequence, Path filePath) {
        try {
            FileOutputStream fileOut = new FileOutputStream(filePath.toFile());
            ObjectOutputStream out = new ObjectOutputStream(fileOut);
            out.writeObject(
                    new Pair<>(commandSequence, validationCommandSequence));
            out.close();
            fileOut.close();
            System.out.println("Serialized data is saved in " +
                    filePath.toString());
        } catch (IOException i) {
            i.printStackTrace();
            return;
        }
    }

    public static void printCommandSequence(CommandSequence cs) {
        System.out.println("Command Sequence:");
        for (String str : cs.getCommandStringList()) {
            System.out.println("str: " + str);
        }
        System.out.println();
    }

    public static int randWithRange(int min, int max) {
        // [min, max)
        return rand.nextInt(max - min) + min;
    }

    public static boolean contains(String val, String[] set) {
        for (String e : set) {
            if (e.equals(val)) {
                return true;
            }
        }
        return false;

    }

    public static String encodeString(String s) {
        String encodedString = Base64.getEncoder().encodeToString(s.getBytes());
        return encodedString;
    }

    public static String decodeString(String s) {
        byte[] decodedBytes = Base64.getDecoder().decode(s);
        String decodedString = new String(decodedBytes);
        return decodedString;
    }

    public static Double randDouble(SplittableRandom rand, Double rangeMin,
            Double rangeMax) {
        double randomValue = rangeMin
                + (rangeMax - rangeMin) * rand.nextDouble();
        return randomValue;
    }

    public static String maskTimeStampHHSS(String str) {
        // remove HH:MM
        String regex = "([0-1]?[0-9]|2[0-3]):[0-5][0-9]";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(str);
        return matcher.replaceAll("");
    }

    public static String maskTimeStampHHMMSS(String str) {
        // remove HH:MM:SS (includes seconds — use only for known timestamp
        // outputs)
        String regex = "([0-1]?[0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(str);
        return matcher.replaceAll("");
    }

    public static String maskTimeStampYYYYMMDD(String str) {
        // remove YYYY-MM-DD
        String regex = "\\d{4}-\\d{2}-\\d{2}";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(str);
        return matcher.replaceAll("");
    }

    // Mask Ruby object (For Hbase)
    public static String maskRubyObject(String str) {
        String filteredOutput = str.replaceAll("(?m)^=> #<Java::.*?$", "")
                .trim();
        return filteredOutput;
    }

    public static int[] computeDiffBrokenInv(
            int[] lastBrokenInv,
            int[] curBrokenInv) {
        assert lastBrokenInv.length == curBrokenInv.length;
        int len = lastBrokenInv.length;
        int[] diffBrokenInv = new int[len];
        for (int i = 0; i < len; i++) {
            diffBrokenInv[i] = curBrokenInv[i] - lastBrokenInv[i];
        }
        return diffBrokenInv;
    }

    public static List<Integer> extractTestIDs(
            StackedTestPacket stackedTestPacket) {
        List<Integer> testIDs = new LinkedList<>();
        for (TestPacket tp : stackedTestPacket.getTestPacketList()) {
            testIDs.add(tp.testPacketID);
        }
        return testIDs;
    }

    public static long generateRandomLong(long min, long max) {
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    public static String maskScanTime(String s) {
        return s.replaceAll("Took [0-9]+.[0-9]+ seconds", "");
    }

    public static int parseInt(String str) {
        if (str.equals("Integer.MAX_VALUE"))
            return Integer.MAX_VALUE;
        if (str.equals("Integer.MIN_VALUE"))
            return Integer.MIN_VALUE;
        return Integer.parseInt(str);
    }

    public static void setRandomDeleteItems(Set<?> set, int numItemsToDelete) {
        if (numItemsToDelete > set.size()) {
            throw new IllegalArgumentException(
                    "Number of items to delete exceeds set size.");
        }

        List<?> list = new ArrayList<>(set);

        for (int i = 0; i < numItemsToDelete; i++) {
            int randomIndex = rand.nextInt(list.size());
            set.remove(list.get(randomIndex));
            list.remove(randomIndex);
        }
    }

    public static boolean setRandomDeleteAtLeaseOneItem(Set<?> set) {
        if (set.size() == 0) {
            return false;
        }
        // numItemsToDelete: [1, set.size()]
        int numItemsToDelete = randWithRange(1, set.size() + 1);
        List<?> list = new ArrayList<>(set);

        for (int i = 0; i < numItemsToDelete; i++) {
            int randomIndex = rand.nextInt(list.size());
            set.remove(list.get(randomIndex));
            list.remove(randomIndex);
        }
        return true;
    }

    public static void serializeSingleCommand(String cmd, OutputStream os)
            throws IOException {
        byte[] cmdBytes = cmd.getBytes(StandardCharsets.UTF_8);
        int cmdLen = cmdBytes.length;
        ByteBuffer buffer = ByteBuffer.allocate(4); // Integer.SIZE / Byte.SIZE
        buffer.putInt(cmdLen);
        byte[] lengthBytes = buffer.array();
        os.write(lengthBytes);
        os.write(cmdBytes);

        os.flush();
    }

    public static String deserializeSingleCommandResult(DataInputStream dis)
            throws IOException {
        int readLength = dis.readInt();
        byte[] messageBytes = new byte[readLength];
        dis.readFully(messageBytes);
        return new String(messageBytes);
    }

    public static int pickWeightedRandomChoice(double[] cumulativeProbabilities,
            double r) {
        // Determine which choice to pick
        for (int i = 0; i < cumulativeProbabilities.length; i++) {
            if (r <= cumulativeProbabilities[i]) {
                return i;
            }
        }
        // Fallback (should never happen if probabilities sum to 1)
        assert false : "the accumulated probability should sum to 1.0.";
        return cumulativeProbabilities.length - 1;
    }

    public static void copyDir(Path sourceDir, Path targetDir)
            throws IOException {
        try {
            FileUtils.copyDirectory(sourceDir.toFile(), targetDir.toFile());
        } catch (IOException e) {
            System.err.println("Error occurred: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void createDirIfNotExist(Path dir) {
        if (!dir.toFile().exists()) {
            boolean status = dir.toFile().mkdirs();
            if (!status) {
                throw new RuntimeException("Cannot create corpusDir: " + dir);
            }
        }
    }

    // sleepTime: seconds
    public static void sleep(int sleepTime) {
        try {
            Thread.sleep(sleepTime * 1000L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // sleepTime: seconds
    public static void sleepAndExit(int sleepTime) {
        try {
            Thread.sleep(sleepTime * 1000L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        logger.info("[Debugging Mode] System exit");
        System.exit(1);
    }

    public static <T> Set<T> subSet(Set<T> targetSet) {
        // if size is 0, return empty set
        if (targetSet.size() == 0) {
            return new HashSet<>();
        }
        List<T> targetSetList = new ArrayList<>(targetSet);
        int numItems = rand.nextInt(targetSetList.size());
        Set<T> subSet = new HashSet<>();
        for (int i = 0; i < numItems; i++) {
            int randomIndex = rand.nextInt(targetSetList.size());
            subSet.add(targetSetList.get(randomIndex));
            targetSetList.remove(randomIndex);
        }
        return subSet;
    }

    public static void filterForwardReadFor14803(
            CommandSequence validationCommandSequence) {
        List<Command> newCommands = new LinkedList<>();
        for (Command command : validationCommandSequence.commands) {
            if (command.constructCommandString().contains(" DESC")) {
                newCommands.add(command);
            }
        }
        validationCommandSequence.commands = newCommands;
    }

    public static class ExponentialProbabilityModel {
        private final double c; // Initial probability
        private final double k; // Decay constant

        public ExponentialProbabilityModel(double c, double targetProbability,
                int targetN) {
            this.c = c;
            this.k = -Math.log(targetProbability / c) / targetN;
        }

        public double calculateProbability(int N) {
            return c * Math.exp(-k * N);
        }
    }

    // json: load map from a file
    public static Map<String, Map<String, String>> loadMapFromFile(
            Path filePath) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(filePath.toFile(), Map.class);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static Map<String, Set<String>> loadStringMapFromFile(
            Path filePath) {
        // Read the map from the JSON file
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            Map<String, Set<String>> mapFromFile = objectMapper.readValue(
                    filePath.toFile(),
                    new TypeReference<Map<String, Set<String>>>() {
                    });
            return mapFromFile;
        } catch (IOException e) {
            System.err.println(
                    "Exception happen when loading output from " + filePath);
            throw new RuntimeException(e);
        }
    }

    public static Map<String, Map<String, String>> computeMF(
            Map<String, Map<String, String>> oriClassInfo,
            Map<String, Map<String, String>> upClassInfo) {
        assert oriClassInfo != null && upClassInfo != null;
        // classname -> fieldName -> fieldType
        // Compute intersection (classname, fieldName and fieldType are the
        // same)
        Map<String, Map<String, String>> mf = new HashMap<>();
        for (String className : oriClassInfo.keySet()) {
            if (upClassInfo.containsKey(className)) {
                Map<String, String> oriFieldInfo = oriClassInfo.get(className);
                Map<String, String> upFieldInfo = upClassInfo.get(className);
                Map<String, String> commonFieldInfo = new HashMap<>();
                for (String fieldName : oriFieldInfo.keySet()) {
                    if (upFieldInfo.containsKey(fieldName)) {
                        String oriFieldType = oriFieldInfo.get(fieldName);
                        String upFieldType = upFieldInfo.get(fieldName);
                        if (oriFieldType.equals(upFieldType)) {
                            commonFieldInfo.put(fieldName, oriFieldType);
                        }
                    }
                }
                if (!commonFieldInfo.isEmpty()) {
                    mf.put(className, commonFieldInfo);
                }
            }
        }
        return mf;
    }

    public static Set<String> computeChangedClasses(
            Map<String, Map<String, String>> oriClassInfo,
            Map<String, Map<String, String>> upClassInfo) {
        assert oriClassInfo != null && upClassInfo != null;
        Set<String> changedClasses = new HashSet<>();
        // classname -> (fieldName, fieldType)
        // For each class, check whether all fields' name and type are the same
        // Otherwise, include it in the return set
        for (String className : oriClassInfo.keySet()) {
            if (!upClassInfo.containsKey(className)
                    || oriClassInfo.get(className).size() != upClassInfo
                            .get(className).size()) {
                changedClasses.add(className);
                continue;
            }
            Map<String, String> oriFieldInfo = oriClassInfo.get(className);
            Map<String, String> upFieldInfo = upClassInfo.get(className);
            // check whether all fields are the same
            for (String fieldName : oriFieldInfo.keySet()) {
                if (!upFieldInfo.containsKey(fieldName) || !oriFieldInfo
                        .get(fieldName).equals(upFieldInfo.get(fieldName))) {
                    changedClasses.add(className);
                    break;
                }
            }
        }
        return changedClasses;
    }

    public static Map<String, Map<String, String>> computeMFUsingModifiedFields(
            Map<String, Map<String, String>> oriClassInfo,
            Map<String, Set<String>> modifiedFields) {
        assert oriClassInfo != null && modifiedFields != null;
        // Extract all field declaration that's not in modifiedFields
        Map<String, Map<String, String>> mf = new HashMap<>();
        for (String className : oriClassInfo.keySet()) {
            // replace $ with . in className
            String classNameReplaced = className.replace("$", ".");
            if (modifiedFields.containsKey(classNameReplaced)) {
                Map<String, String> oriFieldInfo = oriClassInfo.get(className);
                Set<String> modFieldInfo = modifiedFields
                        .get(classNameReplaced);

                // Find all fields that only exist in oriFieldInfo
                Map<String, String> commonFieldInfo = new HashMap<>();
                for (String fieldName : oriFieldInfo.keySet()) {
                    if (!modFieldInfo.contains(fieldName)) {
                        commonFieldInfo.put(fieldName,
                                oriFieldInfo.get(fieldName));
                    }
                }
                if (!commonFieldInfo.isEmpty()) {
                    mf.put(className, commonFieldInfo);
                }
            } else {
                // This means it's not modified at all
                mf.put(className, oriClassInfo.get(className));
            }
        }
        return mf;
    }

    public static Set<String> computeChangedClassesUsingModifiedFields(
            Map<String, Map<String, String>> oriClassInfo,
            Map<String, Set<String>> modifiedFields) {
        assert oriClassInfo != null && modifiedFields != null;
        Set<String> changedClasses = new HashSet<>();
        for (String className : oriClassInfo.keySet()) {
            // replace $ with . in className
            String classNameReplaced = className.replace("$", ".");
            if (modifiedFields.containsKey(classNameReplaced)) {
                Map<String, String> oriFieldInfo = oriClassInfo.get(className);
                Set<String> modFieldInfo = modifiedFields
                        .get(classNameReplaced);
                // Check whether there's an intersection
                for (String fieldName : oriFieldInfo.keySet()) {
                    if (modFieldInfo.contains(fieldName)) {
                        // An intersection => the class is changed
                        changedClasses.add(className);
                        break;
                    }
                }
            }
        }
        return changedClasses;
    }

    public static void printMF(
            Map<String, Map<String, String>> matchableClassInfo) {
        // print matchableClassInfo
        logger.debug("Matchable Class Info:");
        for (Map.Entry<String, Map<String, String>> entry : matchableClassInfo
                .entrySet()) {
            logger.debug(entry.getKey() + ": " + entry.getValue());
        }
    }

    public static Map<String, Map<Integer, Set<SerializationInfo.MergePointInfo>>> loadDumpPoints(
            Path filePath) {
        // Read the map from the JSON file
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            Map<String, Map<Integer, Set<SerializationInfo.MergePointInfo>>> mapFromFile = objectMapper
                    .readValue(filePath.toFile(),
                            new TypeReference<Map<String, Map<Integer, Set<SerializationInfo.MergePointInfo>>>>() {
                            });
            return mapFromFile;
        } catch (IOException e) {
            System.err.println(
                    "Exception happen when loading output from " + filePath);
            throw new RuntimeException(e);
        }
    }

    public static int count(
            Map<String, Map<String, String>> matchableClassInfo) {
        int count = 0;
        for (Map.Entry<String, Map<String, String>> entry : matchableClassInfo
                .entrySet()) {
            count += entry.getValue().size();
        }
        return count;
    }

    public static int countMergePoints(
            Map<String, Map<Integer, Set<SerializationInfo.MergePointInfo>>> objectMergePoints) {
        int count = 0;
        for (Map<Integer, Set<SerializationInfo.MergePointInfo>> mergePoints : objectMergePoints
                .values()) {
            for (Set<SerializationInfo.MergePointInfo> mergePointInfos : mergePoints
                    .values()) {
                count += mergePointInfos.size();
            }
        }
        return count;
    }

    public static Map<String, Map<String, String>> replaceDollarWithDot(
            Map<String, Map<String, String>> classInfo) {
        // Class => {fieldname, fieldtype}, only replace $ for classname
        Map<String, Map<String, String>> newClassInfo = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : classInfo
                .entrySet()) {
            String className = entry.getKey();
            // deep copy fields
            Map<String, String> fields = new HashMap<>();
            for (Map.Entry<String, String> fieldEntry : entry.getValue()
                    .entrySet()) {
                String fieldName = fieldEntry.getKey();
                String fieldType = fieldEntry.getValue();
                fields.put(fieldName, fieldType);
            }
            newClassInfo.put(className.replace("$", "."), fields);
        }
        return newClassInfo;
    }

    // concate variable length of strings with " "
    public static String concat(String... strings) {
        StringBuilder sb = new StringBuilder();
        for (String s : strings) {
            sb.append(s);
            sb.append(" ");
        }
        return sb.toString();
    }
}