package org.zlab.upfuzz.hbase;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.hbase.ddl.CLONE_TABLE_SCHEMA;
import org.zlab.upfuzz.hbase.ddl.DROP;
import org.zlab.upfuzz.hbase.dml.TRUNCATE;
import org.zlab.upfuzz.hbase.dml.TRUNCATE_PRESERVE;
import org.zlab.upfuzz.hbase.tools.WAL_ROLL;

class HBaseCommandSanityTest {

    @BeforeEach
    void initConfig() {
        if (Config.getConf() == null) {
            new Config();
        }
        Config.getConf().enableCheckpointRestore = false;
    }

    @Test
    void dropChoosesOnlyDisabledTables() {
        HBaseState state = new HBaseState();
        state.addTable("enabled_table");
        state.addTable("disabled_table");
        state.disableTable("disabled_table");

        DROP drop = new DROP(state);
        String command = drop.constructCommandString();

        assertTrue(command.contains("disabled_table"));
        assertFalse(command.contains("enabled_table"));
    }

    @Test
    void walRollIsNotRegisteredUntilServerNameGenerationIsFixed() {
        HBaseCommandPool pool = new HBaseCommandPool();

        assertFalse(pool.commandClassList.stream()
                .anyMatch(entry -> entry.getKey().equals(WAL_ROLL.class)));
    }

    @Test
    void checkpointRestoreSkipsLongRunningAdminCommands() {
        Config.getConf().enableCheckpointRestore = true;
        HBaseCommandPool pool = new HBaseCommandPool();

        assertFalse(pool.commandClassList.stream()
                .anyMatch(entry -> entry.getKey()
                        .equals(CLONE_TABLE_SCHEMA.class)));
        assertFalse(pool.commandClassList.stream()
                .anyMatch(entry -> entry.getKey().equals(TRUNCATE.class)));
        assertFalse(pool.commandClassList.stream()
                .anyMatch(entry -> entry.getKey()
                        .equals(TRUNCATE_PRESERVE.class)));
    }
}
