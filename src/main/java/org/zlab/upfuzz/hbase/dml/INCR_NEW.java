package org.zlab.upfuzz.hbase.dml;

import org.zlab.upfuzz.Parameter;
import org.zlab.upfuzz.ParameterType;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.hbase.HBaseColumnFamily;
import org.zlab.upfuzz.hbase.HBaseCommand;
import org.zlab.upfuzz.hbase.HBaseState;
import org.zlab.upfuzz.hbase.HBaseTypes;
import org.zlab.upfuzz.utils.*;

import java.util.HashMap;

public class INCR_NEW extends HBaseCommand {
    // Syntax: put '<table_name>', '<row_key>', '<column_family:qualifier>',
    // '<value>', [timestamp]

    // New row, column
    public INCR_NEW(HBaseState state) {
        super(state);

        Parameter tableName = chooseTable(state, this, null);
        this.params.add(tableName); // [0] table name

        Parameter columnFamilyName = chooseColumnFamily(state, this, null);
        this.params.add(columnFamilyName); // [1] column family name

        ParameterType.ConcreteType rowKeyType = new ParameterType.NotInCollectionType(
                new ParameterType.NotEmpty(UUIDType.instance),
                (s, c) -> ((HBaseState) s).getRowKey(tableName.toString()),
                null);
        Parameter rowKeyName = rowKeyType
                .generateRandomParameter(state, this);
        this.params.add(rowKeyName); // [2] row key

//            ParameterType.ConcreteType columnsType = // LIST<PAIR<String,TYPEType>>
//                    new ParameterType.NotEmpty(
//                            ParameterType.ConcreteGenericType
//                                    .constructConcreteGenericType(
//                                            PAIRType.instance,
//                                            new ParameterType.NotEmpty(
//                                                    new STRINGType(20)),
//                                            HBaseTypes.TYPEType.instance));
//            Parameter column = columnsType
//                    .generateRandomParameter(state, this);
//            params.add(column); // [3] column2type
        ParameterType.ConcreteType columnType = new STRINGType(20);
        Parameter column = columnType.generateRandomParameter(state, this);
        params.add(column); // [3] column

        // int
        Parameter incrValue = new ParameterType.OptionalType(
                new INTType(1, 100), null).generateRandomParameter(state,
                        this);
        this.params.add(incrValue); // [4] column family name
    }

    @Override
    public String constructCommandString() {
        Parameter tableName = params.get(0);
        Parameter columnFamilyName = params.get(1);
        Parameter rowKey = params.get(2);
        Parameter columnName = params.get(3);
        String colNameStr = columnName.toString();
//        colNameStr = colNameStr.substring(0, colNameStr.indexOf(" "));
        String valueStr = params.get(4).toString();
        if (!valueStr.isEmpty())
            valueStr = ", " + valueStr;
        return "incr "
                + "'" + tableName.toString() + "', "
                + "'" + rowKey.toString() + "', "
                + "'" + columnFamilyName.toString() + ":"
                + colNameStr + "' "
                + valueStr;
    }

    @Override
    public void updateState(State state) {
        Parameter tableName = params.get(0);
        Parameter columnFamilyName = params.get(1);
        Parameter rowKey = params.get(2);
        Parameter col2Type = params.get(3);
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
        // not sure why this occurs
        if (s.table2families.get(tableName.toString())
                .get(columnFamilyName.toString()) == null) {
            s.table2families.get(tableName.toString()).put(
                    columnFamilyName.toString(),
                    new HBaseColumnFamily(columnFamilyName.toString(),
                            col2Type));
            return;
        }

        s.addRowKey(tableName.toString(), rowKey.toString());
        s.table2families.get(tableName.toString())
                .get(columnFamilyName.toString())
                .addColName2Type(col2Type);
    }
}
