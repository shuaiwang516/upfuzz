package org.zlab.upfuzz.hbase;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.zlab.upfuzz.*;
import org.zlab.upfuzz.hbase.configurations.UPDATE_ALL_CONFIG;
import org.zlab.upfuzz.hbase.configurations.UPDATE_CONFIG;
import org.zlab.upfuzz.hbase.ddl.*;
import org.zlab.upfuzz.hbase.dml.*;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.hbase.general.STATUS;
import org.zlab.upfuzz.hbase.general.TABLE_HELP;
import org.zlab.upfuzz.hbase.general.VERSION;
import org.zlab.upfuzz.hbase.general.WHOAMI;
import org.zlab.upfuzz.hbase.namespace.*;
import org.zlab.upfuzz.hbase.procedures.LIST_LOCKS;
import org.zlab.upfuzz.hbase.procedures.LIST_PROCEDURES;
import org.zlab.upfuzz.hbase.quotas.*;
import org.zlab.upfuzz.hbase.rsgroup.ADD_RSGROUP;
import org.zlab.upfuzz.hbase.rsgroup.GET_TABLE_RSGROUP;
import org.zlab.upfuzz.hbase.rsgroup.LIST_GROUPS;
import org.zlab.upfuzz.hbase.security.GRANT;
import org.zlab.upfuzz.hbase.snapshot.*;
import org.zlab.upfuzz.hbase.tools.*;
import org.zlab.upfuzz.utils.Utilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CommandTest extends AbstractTest {
    static Logger logger = LogManager.getLogger(CommandTest.class);

    @BeforeAll
    public static void setUp() {
        Config.instance.system = "hbase";
    }

    @Test
    public void test01() {
        HBaseState s = new HBaseState();

        CREATE cmd01 = new CREATE(s);
        String cmd01str = cmd01.constructCommandString();
        // System.out.println(cmd01str);
        cmd01.updateState(s);

        ALTER_ADD_FAMILY cmd02 = new ALTER_ADD_FAMILY(s);
        String cmd02str = cmd02.constructCommandString();
        // System.out.println(cmd02str);
        cmd02.updateState(s);

        ALTER_DELETE_FAMILY cmd03 = new ALTER_DELETE_FAMILY(s);
        String cmd03str = cmd03.constructCommandString();
        // System.out.println(cmd03str);
        cmd03.updateState(s);

        PUT_NEW cmd07 = new PUT_NEW(s);
        String cmd07str = cmd07.constructCommandString();
        // System.out.println(cmd07str);
        cmd07.updateState(s);

        PUT_NEW cmd04 = new PUT_NEW(s);
        String cmd04str = cmd04.constructCommandString();
        // System.out.println(cmd04str);
        cmd04.updateState(s);

        // PUT_NEW_ITEM cmd05 = new PUT_NEW_ITEM(s);
        // String cmd05str = cmd05.constructCommandString();
        // System.out.println(cmd05str);
        // cmd05.updateState(s);
    }

    @Test
    public void test02() {
        HBaseState s = new HBaseState();

        CREATE cmd01 = new CREATE(s);
        String cmd01str = cmd01.constructCommandString();
        System.out.println(cmd01str);
        cmd01.updateState(s);

        for (int i = 0; i < 5; i++) {
            PUT_NEW cmd02 = new PUT_NEW(s);
            String cmd02str = cmd02.constructCommandString();
            System.out.println(cmd02str);
            cmd02.updateState(s);
        }

        for (int i = 0; i < 41; i++) {
            try {
                GET cmd = new GET(s);
                System.out.println(cmd.constructCommandString());
                cmd.updateState(s);
            } catch (CustomExceptions.EmptyCollectionException e) {
                // GET may fail if random CF has no qualifiers
            }
        }
    }

    @Test
    public void test03() {
        HBaseState s = new HBaseState();

//        for (int i = 0; i < 20; i++) {
        CREATE cmd01 = new CREATE(s);
        String cmd01str = cmd01.constructCommandString();
        System.out.println(cmd01str);
        cmd01.updateState(s);
//        }

        for (int i = 0; i < 20; i++) {
            LIST cmd02 = new LIST(s);
            String cmd02str = cmd02.constructCommandString();
            System.out.println(cmd02str);
            cmd02.updateState(s);
        }

        for (int i = 0; i < 20; i++) {
            LIST_NAMESPACE cmd02 = new LIST_NAMESPACE(s);
            String cmd02str = cmd02.constructCommandString();
            System.out.println(cmd02str);
            cmd02.updateState(s);
        }
    }

    @Test
    public void test04() {
        HBaseState s = new HBaseState();

        CREATE cmd01 = new CREATE(s);
        String cmd01str = cmd01.constructCommandString();
        System.out.println(cmd01str);
        cmd01.updateState(s);

//        SCAN cmd04 = new SCAN(s);
//        String cmd04str = cmd04.constructCommandString();
//        System.out.println(cmd04str);
//        cmd04.updateState(s);

        for (int i = 0; i < 20; i++) {
            PUT_NEW cmd02 = new PUT_NEW(s);
            String cmd02str = cmd02.constructCommandString();
            System.out.println(cmd02str);
            cmd02.updateState(s);
        }

        SCAN cmd05 = new SCAN(s);
        String cmd05str = cmd05.constructCommandString();
        System.out.println(cmd05str);
        cmd05.updateState(s);

        System.out.println("# put modify start from here");

        for (int i = 0; i < 15; i++) {
            PUT_MODIFY cmd03 = new PUT_MODIFY(s);
            String cmd03str = cmd03.constructCommandString();
            System.out.println(cmd03str);
            cmd03.updateState(s);
        }

        SCAN cmd06 = new SCAN(s);
        String cmd06str = cmd06.constructCommandString();
        System.out.println(cmd06str);
        cmd06.updateState(s);

        for (int i = 0; i < 15; i++) {
            APPEND cmd03 = new APPEND(s);
            String cmd03str = cmd03.constructCommandString();
            System.out.println(cmd03str);
            cmd03.updateState(s);
        }

        for (int i = 0; i < 15; i++) {
            SCAN cmd07 = new SCAN(s);
            String cmd07str = cmd07.constructCommandString();
            System.out.println(cmd07str);
            cmd07.updateState(s);
        }
    }

    @Test
    public void test05() {
        HBaseState s = new HBaseState();

        CREATE cmd01 = new CREATE(s);
        String cmd01str = cmd01.constructCommandString();
        System.out.println(cmd01str);
        cmd01.updateState(s);

        for (int i = 0; i < 20; i++) {
            PUT_NEW cmd02 = new PUT_NEW(s);
            String cmd02str = cmd02.constructCommandString();
            System.out.println(cmd02str);
            cmd02.updateState(s);
        }

        for (int i = 0; i < 20; i++) {
            DELETEALL cmd02 = new DELETEALL(s);
            String cmd02str = cmd02.constructCommandString();
            System.out.println(cmd02str);
            cmd02.updateState(s);
        }
    }

    @Test
    public void test06() {
        HBaseState s = new HBaseState();

        for (int i = 0; i < 10; i++) {
            CREATE cmd01 = new CREATE(s);
            String cmd01str = cmd01.constructCommandString();
            System.out.println(cmd01str);
            cmd01.updateState(s);
        }

//        for (int i = 0; i < 5; i++) {
//            CLONE_TABLE_SCHEMA cmd02 = new CLONE_TABLE_SCHEMA(s);
//            String cmd02str = cmd02.constructCommandString();
//            System.out.println(cmd02str);
//            cmd02.updateState(s);
//        }
    }

    @Test
    public void CommandTests() throws Exception {
        test01();
    }

    @Test
    public void GrantTest() throws Exception {

        HBaseState s = new HBaseState();

        for (int i = 0; i < 5; i++) {
            CREATE cmd01 = new CREATE(s);
            String cmd01str = cmd01.constructCommandString();
            System.out.println(cmd01str);
            cmd01.updateState(s);
        }
//        for (int i = 0; i < 20; i++) {
//            GRANT cmd02 = new GRANT(s);
//            String cmd02str = cmd02.constructCommandString();
//            System.out.println(cmd02str);
//            cmd02.updateState(s);
//        }
    }

    @Test
    public void testScanTimeMask() {
        String a = "ROW  COLUMN+CELL\n" +
                "0 row(s)\n" +
                "Took 0.0116 seconds";
        // If a string matches Took 0.0116 seconds, remove it
        String b = Utilities.maskScanTime(a);
        // System.out.println(b);
        assert !b.contains("Took 0.0116 seconds");
    }

    @Test
    public void testCREATE() {
        HBaseState s = new HBaseState();

        CREATE cmd01 = new CREATE(s);
        String cmd01str = cmd01.constructCommandString();
        System.out.println(cmd01str);
        cmd01.updateState(s);

        for (int i = 0; i < 20; i++) {
            PUT_NEW cmd07 = new PUT_NEW(s);
            String cmd07str = cmd07.constructCommandString();
            System.out.println(cmd07str);
            cmd07.updateState(s);
        }

        for (int i = 0; i < 100; i++) {
            COUNT cmd07 = new COUNT(s);
            String cmd07str = cmd07.constructCommandString();
            if (cmd07str.contains("FILTER =>")) {
                System.out.println(cmd07str);
                cmd07.updateState(s);
                // to see the mutation, you should catch FILTERType's in the
                // mutate method of ParameterType to force mutation
                try {
                    cmd07.mutate(s);
                    cmd07str = cmd07.constructCommandString();
                    System.out.println(cmd07str);
                    cmd07.updateState(s);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Test
    public void testFilter() {
        HBaseState s = new HBaseState();

        CREATE cmd01 = new CREATE(s);
        String cmd01str = cmd01.constructCommandString();
        System.out.println(cmd01str);
        cmd01.updateState(s);

    }

    @Test
    public void testAlter() {
        try {
            HBaseState s = execInitCommands();
            Command cmd = new ALTER_CF_OPTION(s);
            cmd.updateState(s);
            System.out.println(cmd.constructCommandString());
        } catch (CustomExceptions.EmptyCollectionException e) {
            // Exception is normal, but could be avoided
        }
    }

    @Test
    public void testAlterStatus() {
        HBaseState s = execInitCommands();
        Command cmd = new ALTER_STATUS(s);
        cmd.updateState(s);
        System.out.println(cmd.constructCommandString());
    }

    @Test
    public void testCloneTableSchema() {
        HBaseState s = execInitCommands();
        Command cmd = new CLONE_TABLE_SCHEMA(s);
        cmd.updateState(s);
        System.out.println(cmd.constructCommandString());
    }

    @Test
    public void testPutModify() {
        try {
            HBaseState s = execInitCommands();
            Command cmd = new PUT_MODIFY(s);
            cmd.updateState(s);
            System.out.println(cmd.constructCommandString());
        } catch (CustomExceptions.EmptyCollectionException e) {
            // Exception is normal, but could be avoided
        }
    }

    @Test
    public void test() {
        try {
            HBaseState s = execInitCommands();

            Command cmd = new FLUSH(s);
            cmd.updateState(s);
            System.out.println(cmd.constructCommandString());

            // cmd = new ALTER_NAMESPACE(s);
            // cmd.updateState(s);
            // System.out.println(cmd.constructCommandString());
        } catch (CustomExceptions.EmptyCollectionException e) {
            // Exception is normal, but could be avoided
            e.printStackTrace();
        }
    }

    @Test
    public void testPut() {
        HBaseState s = new HBaseState();
        Command c = new CREATE(s);
        c.updateState(s);
        System.out.println(c.constructCommandString());

        Command c2 = new COMPACT_RS(s);
        c2.updateState(s);
        System.out.println(c2.constructCommandString());
    }

    public static HBaseState execInitCommands() {
        HBaseState s = new HBaseState();

        Command c = new CREATE(s);
        c.updateState(s);
        System.out.println(c.constructCommandString());

        c = new CREATE(s);
        c.updateState(s);
        System.out.println(c.constructCommandString());

        Command c2 = new PUT_NEW(s);
        c2.updateState(s);
        System.out.println(c2.constructCommandString());
        Command c3 = new PUT_NEW(s);
        c3.updateState(s);
        System.out.println(c3.constructCommandString());
        Command c4 = new PUT_NEW(s);
        c4.updateState(s);
        System.out.println(c4.constructCommandString());
        return s;
    }

    // @Test
    // could lead to stack overflow:
    // java.lang.StackOverflowError
    // at org.zlab.upfuzz.Parameter.toString(Parameter.java:107)
    // at
    // org.zlab.upfuzz.ParameterType$InCollectionType.generateStringValue(ParameterType.java:516)
    public void test07() {
        HBaseState s = execInitCommands();
        List<HBaseCommand> objectList = new ArrayList<>();

        HBaseCommand cmd01 = new INCR_NEW(s);
        HBaseCommand cmd02 = new DELETE(s);
        HBaseCommand cmd03 = new DELETEALL(s);
        HBaseCommand cmd04 = new PUT_NEW(s);
        HBaseCommand cmd05 = new INCR_EXISTING(s);
        HBaseCommand cmd06 = new CREATE(s);

        cmd01.updateState(s);
        cmd02.updateState(s);
        cmd03.updateState(s);
        cmd04.updateState(s);
        cmd05.updateState(s);
        cmd06.updateState(s);

        objectList.add(cmd01);
        objectList.add(cmd02);
        objectList.add(cmd03);
        objectList.add(cmd04);
        objectList.add(cmd05);
//        objectList.add(cmd06);

        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < objectList.size(); j++) {
                HBaseCommand cmd = objectList.get(j);
                String op = cmd.constructCommandString();
                System.out.println(op);
                try {
                    cmd.mutate(s);
                } catch (Exception e) {
                    System.out.println("mutate on cmd0" + (j % 5 + 1)
                            + " failed @ iteration: " + i);
                    System.out.println(cmd.params.size());
                    e.printStackTrace();
                    return;
                }
                cmd.updateState(s);
            }
        }
    }

    @Test
    public void test08() {

        HBaseState s = execInitCommands();
        List<HBaseCommand> objectList = new ArrayList<>();

        // Commands may throw if random state selection picks a table/cf
        // without the required prerequisites; wrap each in try/catch.
        Class<?>[] cmdClasses = { PUT_MODIFY.class, APPEND.class, COUNT.class,
                GET.class, SCAN.class, GRANT.class, CREATE_NAMESPACE.class };
        for (Class<?> clazz : cmdClasses) {
            try {
                HBaseCommand cmd = (HBaseCommand) clazz
                        .getConstructor(HBaseState.class).newInstance(s);
                cmd.updateState(s);
                objectList.add(cmd);
            } catch (Exception e) {
                // Construction may fail on sparse state; skip
            }
        }

        try {
            HBaseCommand cmd08 = new LIST_NAMESPACE(s);
            cmd08.updateState(s);
            System.out.println(cmd08.constructCommandString());
        } catch (CustomExceptions.EmptyCollectionException e) {
            // may fail if no namespaces exist
        }

        for (int i = 0; i < 1000; i++) {
            for (int j = 0; j < objectList.size(); j++) {
                HBaseCommand cmd = objectList.get(j);
                String op = cmd.constructCommandString();
//                System.out.println(op);
                try {
                    cmd.mutate(s);
                } catch (Exception e) {
                    System.out.println("mutate on cmd0" + (j % 5 + 1)
                            + " failed @ iteration: " + i);
                    System.out.println(cmd.params.size());
                    e.printStackTrace();
                    return;
                }
                cmd.updateState(s);
            }
        }
    }

    @Test
    public void test09() {
        HBaseState s = execInitCommands();

        List<HBaseCommand> objectList = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            HBaseCommand cmd01 = new CREATE(s);
            cmd01.updateState(s);
            objectList.add(cmd01);

            HBaseCommand cmd02 = new LIST(s);
            cmd02.updateState(s);
            objectList.add(cmd02);

            HBaseCommand cmd03 = new CLONE_TABLE_SCHEMA(s);
            cmd03.updateState(s);
            objectList.add(cmd03);
        }
        for (int i = 0; i < 100; i++) {
            for (HBaseCommand cmd01 : objectList) {
                String op = cmd01.constructCommandString();
//            System.out.println(op);
                try {
                    cmd01.mutate(s);
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }
//                cmd01.updateState(s);    // for testing mutation, I don't see the point of this
            }
//            System.out.print(i);
        }
    }

//    my name is pratikshya
    @Test
    public void testCOMMON() {
        HBaseState s = execInitCommands();
        try {

            Command cmd = new SNAPSHOT(s);
            cmd.updateState(s);
            System.out.println(cmd.constructCommandString());

            cmd = new GET_TABLE_RSGROUP(s);
            cmd.updateState(s);
            System.out.println(cmd.constructCommandString());

        } catch (CustomExceptions.EmptyCollectionException e) {
            // Exception is normal, but could be avoided
            e.printStackTrace();
        }
    }

    @Test
    public void test1() {
        String strs = "disable_exceed_throttle_quota, disable_rpc_throttle, enable_exceed_throttle_quota, enable_rpc_throttle, list_quota_snapshots, list_quota_table_sizes, list_quotas, list_snapshot_sizes, set_quota";
        for (String str : strs.strip().split(",")) {
            System.out.println(str + ", " + str.toUpperCase());
        }
    }

    @Test
    public void testCreateNamespace() {
        HBaseState s = new HBaseState();

        for (int i = 0; i < 5; i++) {
            CREATE_NAMESPACE cmd01 = new CREATE_NAMESPACE(s);
            String cmd01str = cmd01.constructCommandString();
            System.out.println(cmd01str);
            cmd01.updateState(s);
        }
    }

    @Test
    public void testIncrExisting() {
        HBaseState s = execInitCommands();
        // Retry construction — random CF selection may pick one without
        // columns, matching the real generator's retry behavior.
        INCR_EXISTING cmd = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                cmd = new INCR_EXISTING(s);
                break;
            } catch (CustomExceptions.EmptyCollectionException e) {
                // retry with different random choices
            }
        }
        if (cmd == null) {
            System.out.println("INCR_EXISTING: could not construct on "
                    + "available state, skipping");
            return;
        }
        String output = "";
        output = cmd.constructCommandString();
        System.out.println(output);
        cmd.updateState(s);
        for (int i = 0; i < 10; i++) {
            try {
                cmd.mutate(s);
            } catch (Exception e) {
                System.out
                        .println("INCR_EXISTING mutation #: " + i + " failed");
            }
            output = cmd.constructCommandString();
            System.out.println(output);
            cmd.updateState(s);
        }
    }

    @Test
    public void grandTest() {
        HBaseState s = new HBaseState();
        List<HBaseCommand> objectList = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            HBaseCommand cmd = new CREATE(s);
            objectList.add(cmd);
            cmd.updateState(s);
        }

        INCR_EXISTING cmd01 = null;
        INCR_NEW cmd02 = null;
        PUT_NEW cmd03 = null;
        APPEND cmd04 = null;
        COUNT cmd05 = null;
        SCAN cmd06 = null;
        PUT_MODIFY cmd07 = null;
        DELETE cmd08 = null;
        DELETEALL cmd09 = null;
        GET cmd10 = null;
        CLONE_TABLE_SCHEMA cmd11 = null;
        LIST_REGIONS cmd12 = null;
        LIST cmd13 = null;
        CREATE cmd14 = null;
        try {
            cmd01 = new INCR_EXISTING(s);
        } catch (CustomExceptions.EmptyCollectionException e) {
            System.out.println("INCR_EXISTING(s) failed");
        }
        try {
            cmd02 = new INCR_NEW(s);
        } catch (CustomExceptions.EmptyCollectionException e) {
            System.out.println("INCR_NEW(s) failed");
        }
        try {
            cmd03 = new PUT_NEW(s);
        } catch (CustomExceptions.EmptyCollectionException e) {
            System.out.println("PUT_NEW(s) failed");
        }
        try {
            cmd04 = new APPEND(s);
        } catch (CustomExceptions.EmptyCollectionException e) {
            System.out.println("APPEND(s) failed");
        }
        try {
            cmd05 = new COUNT(s);
        } catch (CustomExceptions.EmptyCollectionException e) {
            System.out.println("COUNT(s) failed");
        }
        try {
            cmd06 = new SCAN(s);
        } catch (CustomExceptions.EmptyCollectionException e) {
            System.out.println("SCAN(s) failed");
        }
        try {
            cmd07 = new PUT_MODIFY(s);
        } catch (CustomExceptions.EmptyCollectionException e) {
            System.out.println("PUT_MODIFY(s) failed");
        }
        try {
            cmd08 = new DELETE(s);
        } catch (CustomExceptions.EmptyCollectionException e) {
            System.out.println("DELETE(s) failed");
        }
        try {
            cmd09 = new DELETEALL(s);
        } catch (CustomExceptions.EmptyCollectionException e) {
            System.out.println("DELETEALL(s) failed");
        }
        try {
            cmd10 = new GET(s);
        } catch (CustomExceptions.EmptyCollectionException e) {
            System.out.println("GET(s) failed");
        }
        try {
            cmd11 = new CLONE_TABLE_SCHEMA(s);
        } catch (CustomExceptions.EmptyCollectionException e) {
            System.out.println("CLONE_TABLE_SCHEMA(s) failed");
        }
        try {
            cmd12 = new LIST_REGIONS(s);
        } catch (CustomExceptions.EmptyCollectionException e) {
            System.out.println("LIST_REGIONS(s) failed");
        }
        try {
            cmd13 = new LIST(s);
        } catch (CustomExceptions.EmptyCollectionException e) {
            System.out.println("LIST(s) failed");
        }
        try {
            cmd14 = new CREATE(s);
        } catch (CustomExceptions.EmptyCollectionException e) {
            System.out.println("CREATE(s) failed");
        }

        objectList.add(cmd01);
        objectList.add(cmd02);
        objectList.add(cmd03);
        objectList.add(cmd04);
        objectList.add(cmd05);
        objectList.add(cmd06);
        objectList.add(cmd07);
        objectList.add(cmd08);
        objectList.add(cmd09);
        objectList.add(cmd10);
        objectList.add(cmd11);
        objectList.add(cmd12);
        objectList.add(cmd13);
        objectList.add(cmd14);
        int notNull = 0;
        for (HBaseCommand c : objectList) {
            if (c != null)
                notNull += 1;
        }
        System.out.println("notNull count: " + notNull);
        String output;
        for (int i = 0; i < 10; i++) {
            for (HBaseCommand c : objectList) {
                if (c == null)
                    continue;
                output = c.constructCommandString();
//                System.out.println(c.constructCommandString());
                c.updateState(s);
                try {
                    c.mutate(s);
                } catch (Exception e) {
                    System.out.println(output + " failed");
                }
            }
        }
    }

    // @Test
    public void testAllHBaseCommands() {

        HBaseState s = new HBaseState();
        List<HBaseCommand> objectList = new ArrayList<>();

        // create some tables, and add entries to them
        for (int i = 0; i < 10; i++) {
            HBaseCommand cmd = new CREATE(s);
            cmd.updateState(s);
            for (int j = 0; j < 20; j++) {
                cmd = new PUT_NEW(s);
                cmd.updateState(s);
            }
        }

        UPDATE_ALL_CONFIG cmd01 = null;
        UPDATE_CONFIG cmd02 = null;
        ALTER_ADD_FAMILY cmd03 = null;
        ALTER_CF_OPTION cmd04 = null;
        ALTER_DELETE_FAMILY cmd05 = null;
        ALTER_STATUS cmd06 = null;
        CLONE_TABLE_SCHEMA cmd07 = null;
        CREATE cmd08 = null;
        DESCRIBE cmd09 = null;
        DISABLE cmd10 = null;
        DROP cmd11 = null;
        ENABLE cmd12 = null;
        EXISTS cmd13 = null;
        IS_DISABLED cmd14 = null;
        IS_ENABLED cmd15 = null;
        LIST cmd16 = null;
        LIST_REGIONS cmd17 = null;
        LOCATE_REGION cmd18 = null;
        SHOW_FILTERS cmd19 = null;
        APPEND cmd20 = null;
        COUNT cmd21 = null;
        DELETE cmd22 = null;
        DELETEALL cmd23 = null;
        GET cmd24 = null;
        GET_COUNTER cmd25 = null;
        GET_SPLITS cmd26 = null;
        INCR_EXISTING cmd27 = null;
        INCR_NEW cmd28 = null;
        PUT_MODIFY cmd29 = null;
        PUT_NEW cmd30 = null;
        SCAN cmd31 = null;
        TRUNCATE cmd32 = null;
        TRUNCATE_PRESERVE cmd33 = null;
        STATUS cmd34 = null;
        TABLE_HELP cmd35 = null;
        VERSION cmd36 = null;
        WHOAMI cmd37 = null;
        CREATE_NAMESPACE cmd38 = null;
        DESCRIBE_NAMESPACE cmd39 = null;
        DROP_NAMESPACE cmd40 = null;
        LIST_NAMESPACE cmd41 = null;
        LIST_NAMESPACE_TABLES cmd42 = null;
        LIST_LOCKS cmd43 = null;
        LIST_PROCEDURES cmd44 = null;
        DISABLE_EXCEED_THROTTLE_QUOTA cmd45 = null;
        DISABLE_RPC_THROTTLE cmd46 = null;
        ENABLE_EXCEED_THROTTLE_QUOTA cmd47 = null;
        ENABLE_RPC_THROTTLE cmd48 = null;
        LIST_QUOTAS cmd49 = null;
        LIST_QUOTA_SNAPSHOTS cmd50 = null;
        LIST_QUOTA_TABLE_SIZES cmd51 = null;
        LIST_SNAPSHOT_SIZES cmd52 = null;
        SET_QUOTA_SPACE cmd53 = null;
        SET_QUOTA_THROTTLE_REQUEST cmd54 = null;
        SET_QUOTA_THROTTLE_RW cmd55 = null;
        ADD_RSGROUP cmd56 = null;
        GET_TABLE_RSGROUP cmd57 = null;
        LIST_GROUPS cmd58 = null;
        GRANT cmd59 = null;
        CLONE_SNAPSHOT cmd60 = null;
        DELETE_SNAPSHOT cmd61 = null;
        LIST_SNAPSHOTS cmd62 = null;
        RESTORE_SNAPSHOT cmd63 = null;
        SNAPSHOT cmd64 = null;
        BALANCER cmd65 = null;
        BALANCER_ENABLED cmd66 = null;
        BALANCE_SWITCH_R cmd67 = null;
        BALANCE_SWITCH_W cmd68 = null;
        CATALOGJANITOR_ENABLED cmd69 = null;
        CATALOGJANITOR_RUN cmd70 = null;
        CATALOGJANITOR_SWITCH cmd71 = null;
        CLEANER_CHORE_ENABLED cmd72 = null;
        CLEANER_CHORE_RUN cmd73 = null;
        CLEANER_CHORE_SWITCH cmd74 = null;
        CLEAR_BLOCK_CACHE cmd75 = null;
        CLEAR_DEADSERVERS cmd76 = null;
        COMPACT cmd77 = null;
        COMPACTION_STATE cmd78 = null;
        COMPACTION_SWITCH cmd79 = null;
        COMPACT_RS cmd80 = null;
        FLUSH cmd81 = null;
        MAJOR_COMPACT cmd82 = null;
        SPLIT cmd83 = null;
        WAL_ROLL cmd84 = null;
        ZK_DUMP cmd85 = null;

        try {
            cmd01 = new UPDATE_ALL_CONFIG(s);
            cmd01.updateState(s);
            objectList.add(cmd01);
        } catch (Exception e) {
            System.out.println("creation of UPDATE_ALL_CONFIG command failed");
        }

        try {
            cmd02 = new UPDATE_CONFIG(s);
            cmd02.updateState(s);
            objectList.add(cmd02);
        } catch (Exception e) {
            System.out.println("creation of UPDATE_CONFIG command failed");
        }

        try {
            cmd03 = new ALTER_ADD_FAMILY(s);
            cmd03.updateState(s);
            objectList.add(cmd03);
        } catch (Exception e) {
            System.out.println("creation of ALTER_ADD_FAMILY command failed");
        }

        try {
            cmd04 = new ALTER_CF_OPTION(s);
            cmd04.updateState(s);
            objectList.add(cmd04);
        } catch (Exception e) {
            System.out.println("creation of ALTER_CF_OPTION command failed");
        }

        try {
            cmd05 = new ALTER_DELETE_FAMILY(s);
            cmd05.updateState(s);
            objectList.add(cmd05);
        } catch (Exception e) {
            System.out
                    .println("creation of ALTER_DELETE_FAMILY command failed");
        }

        try {
            cmd06 = new ALTER_STATUS(s);
            cmd06.updateState(s);
            objectList.add(cmd06);
        } catch (Exception e) {
            System.out.println("creation of ALTER_STATUS command failed");
        }

        try {
            cmd07 = new CLONE_TABLE_SCHEMA(s);
            cmd07.updateState(s);
            objectList.add(cmd07);
        } catch (Exception e) {
            System.out.println("creation of CLONE_TABLE_SCHEMA command failed");
        }

        try {
            cmd08 = new CREATE(s);
            cmd08.updateState(s);
            objectList.add(cmd08);
        } catch (Exception e) {
            System.out.println("creation of CREATE command failed");
        }

        try {
            cmd09 = new DESCRIBE(s);
            cmd09.updateState(s);
            objectList.add(cmd09);
        } catch (Exception e) {
            System.out.println("creation of DESCRIBE command failed");
        }

        try {
            cmd10 = new DISABLE(s);
            cmd10.updateState(s);
            objectList.add(cmd10);
        } catch (Exception e) {
            System.out.println("creation of DISABLE command failed");
        }

        try {
            cmd11 = new DROP(s);
            cmd11.updateState(s);
            objectList.add(cmd11);
        } catch (Exception e) {
            System.out.println("creation of DROP command failed");
        }

        try {
            cmd12 = new ENABLE(s);
            cmd12.updateState(s);
            objectList.add(cmd12);
        } catch (Exception e) {
            System.out.println("creation of ENABLE command failed");
        }

        try {
            cmd13 = new EXISTS(s);
            cmd13.updateState(s);
            objectList.add(cmd13);
        } catch (Exception e) {
            System.out.println("creation of EXISTS command failed");
        }

        try {
            cmd14 = new IS_DISABLED(s);
            cmd14.updateState(s);
            objectList.add(cmd14);
        } catch (Exception e) {
            System.out.println("creation of IS_DISABLED command failed");
        }

        try {
            cmd15 = new IS_ENABLED(s);
            cmd15.updateState(s);
            objectList.add(cmd15);
        } catch (Exception e) {
            System.out.println("creation of IS_ENABLED command failed");
        }

        try {
            cmd16 = new LIST(s);
            cmd16.updateState(s);
            objectList.add(cmd16);
        } catch (Exception e) {
            System.out.println("creation of LIST command failed");
        }

        try {
            cmd17 = new LIST_REGIONS(s);
            cmd17.updateState(s);
            objectList.add(cmd17);
        } catch (Exception e) {
            System.out.println("creation of LIST_REGIONS command failed");
        }

        try {
            cmd18 = new LOCATE_REGION(s);
            cmd18.updateState(s);
            objectList.add(cmd18);
        } catch (Exception e) {
            System.out.println("creation of LOCATE_REGION command failed");
        }

        try {
            cmd19 = new SHOW_FILTERS(s);
            cmd19.updateState(s);
            objectList.add(cmd19);
        } catch (Exception e) {
            System.out.println("creation of SHOW_FILTERS command failed");
        }

        try {
            cmd20 = new APPEND(s);
            cmd20.updateState(s);
            objectList.add(cmd20);
        } catch (Exception e) {
            System.out.println("creation of APPEND command failed");
        }

        try {
            cmd21 = new COUNT(s);
            cmd21.updateState(s);
            objectList.add(cmd21);
        } catch (Exception e) {
            System.out.println("creation of COUNT command failed");
        }

        try {
            cmd22 = new DELETE(s);
            cmd22.updateState(s);
            objectList.add(cmd22);
        } catch (Exception e) {
            System.out.println("creation of DELETE command failed");
        }

        try {
            cmd23 = new DELETEALL(s);
            cmd23.updateState(s);
            objectList.add(cmd23);
        } catch (Exception e) {
            System.out.println("creation of DELETEALL command failed");
        }

        try {
            cmd24 = new GET(s);
            cmd24.updateState(s);
            objectList.add(cmd24);
        } catch (Exception e) {
            System.out.println("creation of GET command failed");
        }

        try {
            cmd25 = new GET_COUNTER(s);
            cmd25.updateState(s);
            objectList.add(cmd25);
        } catch (Exception e) {
            System.out.println("creation of GET_COUNTER command failed");
        }

        try {
            cmd26 = new GET_SPLITS(s);
            cmd26.updateState(s);
            objectList.add(cmd26);
        } catch (Exception e) {
            System.out.println("creation of GET_SPLITS command failed");
        }

        try {
            cmd27 = new INCR_EXISTING(s);
            cmd27.updateState(s);
            objectList.add(cmd27);
        } catch (Exception e) {
            System.out.println("creation of INCR_EXISTING command failed");
        }

        try {
            cmd28 = new INCR_NEW(s);
            cmd28.updateState(s);
            objectList.add(cmd28);
        } catch (Exception e) {
            System.out.println("creation of INCR_NEW command failed");
        }

        try {
            cmd29 = new PUT_MODIFY(s);
            cmd29.updateState(s);
            objectList.add(cmd29);
        } catch (Exception e) {
            System.out.println("creation of PUT_MODIFY command failed");
        }

        try {
            cmd30 = new PUT_NEW(s);
            cmd30.updateState(s);
            objectList.add(cmd30);
        } catch (Exception e) {
            System.out.println("creation of PUT_NEW command failed");
        }

        try {
            cmd31 = new SCAN(s);
            cmd31.updateState(s);
            objectList.add(cmd31);
        } catch (Exception e) {
            System.out.println("creation of SCAN command failed");
        }

        try {
            cmd32 = new TRUNCATE(s);
            cmd32.updateState(s);
            objectList.add(cmd32);
        } catch (Exception e) {
            System.out.println("creation of TRUNCATE command failed");
        }

        try {
            cmd33 = new TRUNCATE_PRESERVE(s);
            cmd33.updateState(s);
            objectList.add(cmd33);
        } catch (Exception e) {
            System.out.println("creation of TRUNCATE_PRESERVE command failed");
        }

        try {
            cmd34 = new STATUS(s);
            cmd34.updateState(s);
            objectList.add(cmd34);
        } catch (Exception e) {
            System.out.println("creation of STATUS command failed");
        }

        try {
            cmd35 = new TABLE_HELP(s);
            cmd35.updateState(s);
            objectList.add(cmd35);
        } catch (Exception e) {
            System.out.println("creation of TABLE_HELP command failed");
        }

        try {
            cmd36 = new VERSION(s);
            cmd36.updateState(s);
            objectList.add(cmd36);
        } catch (Exception e) {
            System.out.println("creation of VERSION command failed");
        }

        try {
            cmd37 = new WHOAMI(s);
            cmd37.updateState(s);
            objectList.add(cmd37);
        } catch (Exception e) {
            System.out.println("creation of WHOAMI command failed");
        }

        try {
            cmd38 = new CREATE_NAMESPACE(s);
            cmd38.updateState(s);
            objectList.add(cmd38);
        } catch (Exception e) {
            System.out.println("creation of CREATE_NAMESPACE command failed");
        }

        try {
            cmd39 = new DESCRIBE_NAMESPACE(s);
            cmd39.updateState(s);
            objectList.add(cmd39);
        } catch (Exception e) {
            System.out.println("creation of DESCRIBE_NAMESPACE command failed");
        }

        try {
            cmd40 = new DROP_NAMESPACE(s);
            cmd40.updateState(s);
            objectList.add(cmd40);
        } catch (Exception e) {
            System.out.println("creation of DROP_NAMESPACE command failed");
        }

        try {
            cmd41 = new LIST_NAMESPACE(s);
            cmd41.updateState(s);
            objectList.add(cmd41);
        } catch (Exception e) {
            System.out.println("creation of LIST_NAMESPACE command failed");
        }

        try {
            cmd42 = new LIST_NAMESPACE_TABLES(s);
            cmd42.updateState(s);
            objectList.add(cmd42);
        } catch (Exception e) {
            System.out.println(
                    "creation of LIST_NAMESPACE_TABLES command failed");
        }

        try {
            cmd43 = new LIST_LOCKS(s);
            cmd43.updateState(s);
            objectList.add(cmd43);
        } catch (Exception e) {
            System.out.println("creation of LIST_LOCKS command failed");
        }

        try {
            cmd44 = new LIST_PROCEDURES(s);
            cmd44.updateState(s);
            objectList.add(cmd44);
        } catch (Exception e) {
            System.out.println("creation of LIST_PROCEDURES command failed");
        }

        try {
            cmd45 = new DISABLE_EXCEED_THROTTLE_QUOTA(s);
            cmd45.updateState(s);
            objectList.add(cmd45);
        } catch (Exception e) {
            System.out.println(
                    "creation of DISABLE_EXCEED_THROTTLE_QUOTA command failed");
        }

        try {
            cmd46 = new DISABLE_RPC_THROTTLE(s);
            cmd46.updateState(s);
            objectList.add(cmd46);
        } catch (Exception e) {
            System.out
                    .println("creation of DISABLE_RPC_THROTTLE command failed");
        }

        try {
            cmd47 = new ENABLE_EXCEED_THROTTLE_QUOTA(s);
            cmd47.updateState(s);
            objectList.add(cmd47);
        } catch (Exception e) {
            System.out.println(
                    "creation of ENABLE_EXCEED_THROTTLE_QUOTA command failed");
        }

        try {
            cmd48 = new ENABLE_RPC_THROTTLE(s);
            cmd48.updateState(s);
            objectList.add(cmd48);
        } catch (Exception e) {
            System.out
                    .println("creation of ENABLE_RPC_THROTTLE command failed");
        }

        try {
            cmd49 = new LIST_QUOTAS(s);
            cmd49.updateState(s);
            objectList.add(cmd49);
        } catch (Exception e) {
            System.out.println("creation of LIST_QUOTAS command failed");
        }

        try {
            cmd50 = new LIST_QUOTA_SNAPSHOTS(s);
            cmd50.updateState(s);
            objectList.add(cmd50);
        } catch (Exception e) {
            System.out
                    .println("creation of LIST_QUOTA_SNAPSHOTS command failed");
        }

        try {
            cmd51 = new LIST_QUOTA_TABLE_SIZES(s);
            cmd51.updateState(s);
            objectList.add(cmd51);
        } catch (Exception e) {
            System.out.println(
                    "creation of LIST_QUOTA_TABLE_SIZES command failed");
        }

        try {
            cmd52 = new LIST_SNAPSHOT_SIZES(s);
            cmd52.updateState(s);
            objectList.add(cmd52);
        } catch (Exception e) {
            System.out
                    .println("creation of LIST_SNAPSHOT_SIZES command failed");
        }

        try {
            cmd53 = new SET_QUOTA_SPACE(s);
            cmd53.updateState(s);
            objectList.add(cmd53);
        } catch (Exception e) {
            System.out.println("creation of SET_QUOTA_SPACE command failed");
        }

        try {
            cmd54 = new SET_QUOTA_THROTTLE_REQUEST(s);
            cmd54.updateState(s);
            objectList.add(cmd54);
        } catch (Exception e) {
            System.out.println(
                    "creation of SET_QUOTA_THROTTLE_REQUEST command failed");
        }

        try {
            cmd55 = new SET_QUOTA_THROTTLE_RW(s);
            cmd55.updateState(s);
            objectList.add(cmd55);
        } catch (Exception e) {
            System.out.println(
                    "creation of SET_QUOTA_THROTTLE_RW command failed");
        }

        try {
            cmd56 = new ADD_RSGROUP(s);
            cmd56.updateState(s);
            objectList.add(cmd56);
        } catch (Exception e) {
            System.out.println("creation of ADD_RSGROUP command failed");
        }

        try {
            cmd57 = new GET_TABLE_RSGROUP(s);
            cmd57.updateState(s);
            objectList.add(cmd57);
        } catch (Exception e) {
            System.out.println("creation of GET_TABLE_RSGROUP command failed");
        }

        try {
            cmd58 = new LIST_GROUPS(s);
            cmd58.updateState(s);
            objectList.add(cmd58);
        } catch (Exception e) {
            System.out.println("creation of LIST_GROUPS command failed");
        }

        try {
            cmd59 = new GRANT(s);
            cmd59.updateState(s);
            objectList.add(cmd59);
        } catch (Exception e) {
            System.out.println("creation of GRANT command failed");
        }

        try {
            cmd60 = new CLONE_SNAPSHOT(s);
            cmd60.updateState(s);
            objectList.add(cmd60);
        } catch (Exception e) {
            System.out.println("creation of CLONE_SNAPSHOT command failed");
        }

        try {
            cmd61 = new DELETE_SNAPSHOT(s);
            cmd61.updateState(s);
            objectList.add(cmd61);
        } catch (Exception e) {
            System.out.println("creation of DELETE_SNAPSHOT command failed");
        }

        try {
            cmd62 = new LIST_SNAPSHOTS(s);
            cmd62.updateState(s);
            objectList.add(cmd62);
        } catch (Exception e) {
            System.out.println("creation of LIST_SNAPSHOTS command failed");
        }

        try {
            cmd63 = new RESTORE_SNAPSHOT(s);
            cmd63.updateState(s);
            objectList.add(cmd63);
        } catch (Exception e) {
            System.out.println("creation of RESTORE_SNAPSHOT command failed");
        }

        try {
            cmd64 = new SNAPSHOT(s);
            cmd64.updateState(s);
            objectList.add(cmd64);
        } catch (Exception e) {
            System.out.println("creation of SNAPSHOT command failed");
        }

        try {
            cmd65 = new BALANCER(s);
            cmd65.updateState(s);
            objectList.add(cmd65);
        } catch (Exception e) {
            System.out.println("creation of BALANCER command failed");
        }

        try {
            cmd66 = new BALANCER_ENABLED(s);
            cmd66.updateState(s);
            objectList.add(cmd66);
        } catch (Exception e) {
            System.out.println("creation of BALANCER_ENABLED command failed");
        }

        try {
            cmd67 = new BALANCE_SWITCH_R(s);
            cmd67.updateState(s);
            objectList.add(cmd67);
        } catch (Exception e) {
            System.out.println("creation of BALANCE_SWITCH_R command failed");
        }

        try {
            cmd68 = new BALANCE_SWITCH_W(s);
            cmd68.updateState(s);
            objectList.add(cmd68);
        } catch (Exception e) {
            System.out.println("creation of BALANCE_SWITCH_W command failed");
        }

        try {
            cmd69 = new CATALOGJANITOR_ENABLED(s);
            cmd69.updateState(s);
            objectList.add(cmd69);
        } catch (Exception e) {
            System.out.println(
                    "creation of CATALOGJANITOR_ENABLED command failed");
        }

        try {
            cmd70 = new CATALOGJANITOR_RUN(s);
            cmd70.updateState(s);
            objectList.add(cmd70);
        } catch (Exception e) {
            System.out.println("creation of CATALOGJANITOR_RUN command failed");
        }

        try {
            cmd71 = new CATALOGJANITOR_SWITCH(s);
            cmd71.updateState(s);
            objectList.add(cmd71);
        } catch (Exception e) {
            System.out.println(
                    "creation of CATALOGJANITOR_SWITCH command failed");
        }

        try {
            cmd72 = new CLEANER_CHORE_ENABLED(s);
            cmd72.updateState(s);
            objectList.add(cmd72);
        } catch (Exception e) {
            System.out.println(
                    "creation of CLEANER_CHORE_ENABLED command failed");
        }

        try {
            cmd73 = new CLEANER_CHORE_RUN(s);
            cmd73.updateState(s);
            objectList.add(cmd73);
        } catch (Exception e) {
            System.out.println("creation of CLEANER_CHORE_RUN command failed");
        }

        try {
            cmd74 = new CLEANER_CHORE_SWITCH(s);
            cmd74.updateState(s);
            objectList.add(cmd74);
        } catch (Exception e) {
            System.out
                    .println("creation of CLEANER_CHORE_SWITCH command failed");
        }

        try {
            cmd75 = new CLEAR_BLOCK_CACHE(s);
            cmd75.updateState(s);
            objectList.add(cmd75);
        } catch (Exception e) {
            System.out.println("creation of CLEAR_BLOCK_CACHE command failed");
        }

        try {
            cmd76 = new CLEAR_DEADSERVERS(s);
            cmd76.updateState(s);
            objectList.add(cmd76);
        } catch (Exception e) {
            System.out.println("creation of CLEAR_DEADSERVERS command failed");
        }

        try {
            cmd77 = new COMPACT(s);
            cmd77.updateState(s);
            objectList.add(cmd77);
        } catch (Exception e) {
            System.out.println("creation of COMPACT command failed");
        }

        try {
            cmd78 = new COMPACTION_STATE(s);
            cmd78.updateState(s);
            objectList.add(cmd78);
        } catch (Exception e) {
            System.out.println("creation of COMPACTION_STATE command failed");
        }

        try {
            cmd79 = new COMPACTION_SWITCH(s);
            cmd79.updateState(s);
            objectList.add(cmd79);
        } catch (Exception e) {
            System.out.println("creation of COMPACTION_SWITCH command failed");
        }

        try {
            cmd80 = new COMPACT_RS(s);
            cmd80.updateState(s);
            objectList.add(cmd80);
        } catch (Exception e) {
            System.out.println("creation of COMPACT_RS command failed");
        }

        try {
            cmd81 = new FLUSH(s);
            cmd81.updateState(s);
            objectList.add(cmd81);
        } catch (Exception e) {
            System.out.println("creation of FLUSH command failed");
        }

        try {
            cmd82 = new MAJOR_COMPACT(s);
            cmd82.updateState(s);
            objectList.add(cmd82);
        } catch (Exception e) {
            System.out.println("creation of MAJOR_COMPACT command failed");
        }

        try {
            cmd83 = new SPLIT(s);
            cmd83.updateState(s);
            objectList.add(cmd83);
        } catch (Exception e) {
            System.out.println("creation of SPLIT command failed");
        }

        try {
            cmd84 = new WAL_ROLL(s);
            cmd84.updateState(s);
            objectList.add(cmd84);
        } catch (Exception e) {
            System.out.println("creation of WAL_ROLL command failed");
        }

        try {
            cmd85 = new ZK_DUMP(s);
            cmd85.updateState(s);
            objectList.add(cmd85);
        } catch (Exception e) {
            System.out.println("creation of WAL_ROLL command failed");
        }

        System.out.println("objectList size: " + objectList.size());

        int constructStringFailures = 0;
        int updateStateFailures = 0;
        int mutateFailures = 0;

        int constructStringSuccesses = 0;
        int updateStateSuccesses = 0;
        int mutateSuccesses = 0;

        for (int i = 0; i < 100; i++) {
            Collections.shuffle(objectList);
            for (int j = 0; j < objectList.size(); j++) {
                HBaseCommand c = objectList.get(j);
                try {
                    c.constructCommandString();
                    constructStringSuccesses++;
                } catch (Exception e) {
                    System.out.println(
                            "constructCommandString fail on cmd" + (j + 1));
                    e.printStackTrace();
                    constructStringFailures++;
                    return;
                }
                try {
                    c.mutate(s);
                    mutateSuccesses++;
                } catch (Exception e) {
                    System.out.println("mutate fail on cmd" + (j + 1));
                    e.printStackTrace();
                    mutateFailures++;
                    return;
                }

                try {
                    c.updateState(s);
                    updateStateSuccesses++;
                } catch (Exception e) {
                    System.out.println("updateState fail on cmd" + (j + 1));
                    e.printStackTrace();
                    updateStateFailures++;
                    return;
                }
            }
        }

        System.out
                .println("constructStringFailures: " + constructStringFailures);
        System.out.println("updateStateFailures: " + updateStateFailures);
        System.out.println("mutateFailures: " + mutateFailures);

        System.out.println(
                "constructStringSuccesses: " + constructStringSuccesses);
        System.out.println("updateStateSuccesses: " + updateStateSuccesses);
        System.out.println("mutateSuccesses: " + mutateSuccesses);
    }
}
