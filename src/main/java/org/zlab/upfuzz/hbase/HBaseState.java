package org.zlab.upfuzz.hbase;

import org.zlab.upfuzz.Parameter;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.utils.Utilities;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class HBaseState extends State {

    public Map<String, Set<String>> namespace2tables = new HashMap<>();
    public Map<String, Map<String, HBaseColumnFamily>> table2families = new HashMap<>();
    public Map<String, Set<String>> table2UDTs = new HashMap<>();
    public Map<String, Boolean> table2enable = new HashMap<>();

    public Map<String, Map<String, HBaseColumnFamily>> snapshots = new HashMap<>();

    // stores the row keys of each table
    public Map<String, Set<String>> table2rowKeys = new HashMap<>();

    public void addRowKeyTable(String tableName) {
        if (table2rowKeys.containsKey(tableName))
            return;
        table2rowKeys.put(tableName, new HashSet<>());
    }

    public void removeRowKeyTable(String tableName) {
        table2rowKeys.remove(tableName);
    }

    public Set<Parameter> getRowKey(String tableName) {
        Set<String> result = new HashSet<>();
        if (table2rowKeys.containsKey(tableName))
            result.addAll(table2rowKeys.get(tableName));
        return Utilities.strings2Parameters(result);
    }

    public void addRowKey(String tableName, String rowKey) {
        if (!table2rowKeys.containsKey(tableName))
            return;
        table2rowKeys.get(tableName).add(rowKey);
    }

    public void deleteRowKey(String tableName, String rowKey) {
        if (!table2rowKeys.containsKey(tableName))
            return;
        table2rowKeys.get(tableName).remove(rowKey);
    }

    public void enableTable(String tableName) {
        table2enable.put(tableName, Boolean.TRUE);
    }

    public void disableTable(String tableName) {
        table2enable.put(tableName, Boolean.FALSE);
    }

    public Map<String, Boolean> getTable2enable() {
        return table2enable;
    }

    public void addColumnFamily(String tableName, String columnFamilyName,
            HBaseColumnFamily columnFamily) {
        if (!table2families.containsKey(tableName))
            return;
        if (table2families.get(tableName) == null) {
            table2families.put(tableName,
                    new HashMap<String, HBaseColumnFamily>());
        }
        table2families.get(tableName).put(columnFamilyName, columnFamily);
    }

    public void deleteColumnFamily(String tableName, String columnFamilyName) {
        if (!table2families.containsKey(tableName))
            return;
        if (table2families.get(tableName) == null) {
            table2families.remove(tableName);
            return;
        }
        table2families.get(tableName).remove(columnFamilyName);
    }

    public void addTable(String tableName) {
        if (!table2families.containsKey(tableName)) {
            table2families.put(tableName, new HashMap<>());
        }
        if (!table2UDTs.containsKey(tableName)) {
            table2UDTs.put(tableName, new HashSet<>());
        }
        if (!table2enable.containsKey(tableName)) {
            table2enable.put(tableName, Boolean.TRUE);
        }
        if (!table2rowKeys.containsKey(tableName)) {
            table2rowKeys.put(tableName, new HashSet<>());
        }
    }

    public void addTable(String tableName,
            Map<String, HBaseColumnFamily> table) {
        table2families.put(tableName, table);
    }

    public void deleteTable(String tableName) {
        table2families.remove(tableName);
        table2UDTs.remove(tableName);
        table2enable.remove(tableName);
        table2rowKeys.remove(tableName);
    }

    public Set<Parameter> getTables() {
        return Utilities.strings2Parameters(table2families.keySet());
    }

    public Set<Parameter> getDisabledTables() {
        Set<String> disabledTables = new HashSet<>();
        for (Map.Entry<String, Boolean> entry : table2enable.entrySet()) {
            if (Boolean.FALSE.equals(entry.getValue())
                    && table2families.containsKey(entry.getKey())) {
                disabledTables.add(entry.getKey());
            }
        }
        return Utilities.strings2Parameters(disabledTables);
    }

    // namespace
    public void addNamespace(String namespace) {
        if (!namespace2tables.containsKey(namespace)) {
            namespace2tables.put(namespace, new HashSet<>());
        }
    }

    public void dropNamespace(String namespace) {
        namespace2tables.remove(namespace);
    }

    public Set<Parameter> getNamespaces() {
        return Utilities.strings2Parameters(namespace2tables.keySet());
    }

    public Set<Parameter> getColumnFamiliesInTable(String tableName) {
        return Utilities
                .strings2Parameters(table2families.get(tableName).keySet());
    }

    public HBaseColumnFamily getColumnFamily(String tableName,
            String columnFamilyName) {
        return table2families.get(tableName).get(columnFamilyName);
    }

    public void addSnapshot(String name, Map<String, HBaseColumnFamily> table) {
        snapshots.put(name, table);
    }

    @Override
    public void clearState() {
        namespace2tables.clear();
        table2families.clear();
        table2UDTs.clear();
        table2enable.clear();

        snapshots.clear();
        table2rowKeys.clear();
    }

}
