package org.zlab.upfuzz.hbase.dml;

import org.zlab.upfuzz.Parameter;
import org.zlab.upfuzz.ParameterType;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.hbase.HBaseCommand;
import org.zlab.upfuzz.hbase.HBaseState;
import org.zlab.upfuzz.hbase.HBaseTypes;
import org.zlab.upfuzz.utils.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class PUT_NEW extends HBaseCommand {
    // syntax: put '<table_name>', '<row_key>', '<column_family:column_name>',
    // 'value' {VISIBILITY=>'PRIVATE|SECRET', ATTRIBUTES=>{'mykey'=>'myvalue'}}

    // New row, column
    public PUT_NEW(HBaseState state) {
        super(state);

        Parameter tableName = chooseTable(state, this, null);
        this.params.add(tableName); // [0] table name

        ParameterType.ConcreteType rowKeyType = new ParameterType.NotInCollectionType(
                new ParameterType.NotEmpty(UUIDType.instance),
                (s, c) -> ((HBaseState) s).getRowKey(tableName.toString()),
                null);
        Parameter rowKeyName = rowKeyType
                .generateRandomParameter(state, this);
        this.params.add(rowKeyName); // [1] row key

        Parameter columnFamilyName = chooseColumnFamily(state, this,
                null);
        this.params.add(columnFamilyName); // [2] column family name

        ParameterType.ConcreteType columnType = new STRINGType(20);
        Parameter column = columnType.generateRandomParameter(state, this);
        params.add(column); // [3] column

        ParameterType.ConcreteType valueType = new ParameterType.NotEmpty(
                new STRINGType(20));
        Parameter value = valueType.generateRandomParameter(state, this);
        this.params.add(value); // [4] value

        Parameter VISIBILITYType = new ParameterType.OptionalType(
                new ParameterType.InCollectionType(
                        CONSTANTSTRINGType.instance,
                        (s, c) -> Utilities
                                .strings2Parameters(
                                        VISIBILITYTypes),
                        null),
                null)
                        .generateRandomParameter(state, this);
        params.add(VISIBILITYType); // [5] visibility
    }

    @Override
    public String constructCommandString() {
        String tableName = params.get(0).toString();
        String rowKey = params.get(1).toString();
        String columnFamilyName = params.get(2).toString();
        String columnName = params.get(3).toString();
        String value = params.get(4).toString();
        String visibility = params.get(5).toString();
        if (visibility.isEmpty()) {
            return String.format(
                    "put '%s', '%s', '%s:%s', '%s'",
                    tableName, rowKey, columnFamilyName, columnName, value);
        }
        return String.format(
                "put '%s', '%s', '%s:%s', '%s', {VISIBILITY=>'%s'}",
                tableName, rowKey, columnFamilyName, columnName, value,
                visibility);
    }

    @Override
    public void updateState(State state) {
        Parameter tableName = params.get(0);
        Parameter rowKey = params.get(1);
        Parameter columnFamilyName = params.get(2);
        Parameter columnName = params.get(3);

        HBaseState s = ((HBaseState) state);

        // checks because the parameters can get mutated
        if (!s.table2families.containsKey(tableName.toString())) {
            return;
        }
        if (s.table2families.get(tableName.toString()) == null) {
            s.table2families.put(tableName.toString(), new HashMap<>());
        }
        if (!s.table2families.get(tableName.toString())
                .containsKey(columnFamilyName.toString())) {
            return;
        }

        // we call addRowKey to add a row key because
        // incr 't1', 'r3', 'cf2' will also create a new row 'r3' in column
        // family 'cf2' if 'r3' previously didn't exist
        s.addRowKey(tableName.toString(), rowKey.toString());

        s.table2families.get(tableName.toString())
                .get(columnFamilyName.toString())
                .addColName2Type(columnName);
    }

    @Override
    public boolean mutate(State s) throws Exception {
        try {
            super.mutate(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
