package org.zlab.upfuzz.hbase.dml;

import org.zlab.upfuzz.Parameter;
import org.zlab.upfuzz.ParameterType;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.hbase.HBaseColumnFamily;
import org.zlab.upfuzz.hbase.HBaseCommand;
import org.zlab.upfuzz.hbase.HBaseState;
import org.zlab.upfuzz.utils.*;

import java.util.*;

public class GET extends HBaseCommand {

    /**
     * Example
     * get "uuid8780ad9e9e9f421b8724b83b09cc9eae", "row1", "JCIWaTQ:c1", "JCIWaTQ:c2"
     *
     * TIMERANGE: [ts1, ts2] |  get 't1', 'r1', {TIMERANGE => [ts1, ts2]} | ts1 & 2 are timestamps
     * COLUMN: done
     * FILTER: | {FILTER => "ValueFilter(=, 'binary:abc')"}
     * VERSIONS: done
     * AUTHORIZATIONS: enum of ['PRIVATE','SECRET', ...?] | get 't1', 'r1', {COLUMN => 'c1', AUTHORIZATIONS => ['PRIVATE','SECRET']}
     * CONSISTENCY: enum {'TIMELINE', 'STRONG'} // https://hbase.apache.org/1.2/apidocs/org/apache/hadoop/hbase/client/Consistency.html
     * REGION_REPLICA_ID: a number? | {CONSISTENCY => 'TIMELINE', REGION_REPLICA_ID => 1}
     * FORMATTER: ?? | {FORMATTER => 'toString'}
     *  formatter can be stipulated as:
     *  1. either as a org.apache.hadoop.hbase.util.Bytes method name (e.g, toInt, toString)
     *  2. or as a custom class followed by method name: e.g. 'c(MyFormatterClass).format'.
     *
     *
    
     * {COLUMN => '%s:%s': '<columnFamily>:<columnName>'
     */
    public GET(HBaseState state) {
        super(state);

        Parameter tableName = chooseTable(state, this, null);
        this.params.add(tableName); // 0 table name

        Parameter rowKey = chooseRowKey(state, this, null);
        this.params.add(rowKey); // 1 row key

        Parameter columnFamilyName = chooseOptionalColumnFamily(state,
                this);
        this.params.add(columnFamilyName); // 2 column family name

        Parameter column;
        // if columnFamily is empty, pick a random column from a table
        // else, pick a column from that column family
        if (columnFamilyName.toString().isEmpty()) {
            ArrayList<Parameter> columns = new ArrayList<>();

            for (HBaseColumnFamily cf : state.table2families
                    .get(tableName.toString()).values()) {
                columns.addAll(cf.colName2Type);
            }

            ParameterType.ConcreteType columnType = new ParameterType.InCollectionType(
                    CONSTANTSTRINGType.instance,
                    (s, c) -> columns,
                    null);

            column = new ParameterType.OptionalType(columnType, null)
                    .generateRandomParameter(state, this);
        } else {

//                column = chooseColumnName(state, this, columnFamilyName.toString(), null);
            ParameterType.ConcreteType columnType = new ParameterType.InCollectionType(
                    CONSTANTSTRINGType.instance,
                    (s, c) -> ((HBaseState) s).table2families
                            .get(c.params.get(0).toString())
                            .get(columnFamilyName.toString()).colName2Type,
                    null);

            column = new ParameterType.OptionalType(columnType, null)
                    .generateRandomParameter(state, this);

        }
        this.params.add(column); // 3 column2type

        Parameter consistency = new ParameterType.OptionalType(
                new ParameterType.InCollectionType(
                        CONSTANTSTRINGType.instance,
                        (s, c) -> Utilities
                                .strings2Parameters(CONSISTENCYTypes),
                        null),
                null).generateRandomParameter(state, this);
        this.params.add(consistency); // 4 consistency

        // AUTHORIZATIONS
        Parameter authorization = new ParameterType.OptionalType(
                new ParameterType.InCollectionType(
                        CONSTANTSTRINGType.instance,
                        (s, c) -> Utilities
                                .strings2Parameters(AUTHORIZATION_TYPES),
                        null),
                null).generateRandomParameter(state, this);
        this.params.add(authorization); // 5 consistency

        // TODO: TIMERANGE

        // FILTER
        // Parameter filter = new ParameterType.OptionalType(new
        // FILTERType(),
        // null).generateRandomParameter(state, this);
        // this.params.add(filter); // [6] filter

        // REGION_REPLICA_ID ig this is related to the # regions used when
        // creating a table
        Parameter regionReplicaID = new ParameterType.OptionalType(
                new INTType(2, 20), null)
                        .generateRandomParameter(state, this);
        this.params.add(regionReplicaID); // 6 region replica id

        Parameter formatter = new ParameterType.OptionalType(
                new ParameterType.InCollectionType(
                        CONSTANTSTRINGType.instance,
                        (s, c) -> Utilities
                                .strings2Parameters(
                                        DEFAULT_FORMATTER_FUNCTIONS),
                        null),
                null).generateRandomParameter(state, this);
        this.params.add(formatter); // 7 formatter
    }

    @Override
    public String constructCommandString() {
        String tableName = this.params.get(0).toString();
        String rowKey = this.params.get(1).toString();
        String columnFamilyName = this.params.get(2).toString();
        String qualifier = this.params.get(3).toString();
        String consistency = this.params.get(4).toString();
        String authorization = this.params.get(5).toString();
        // String filter = this.params.get(6).toString();
        String regionReplicaID = this.params.get(6).toString();
        String formatter = this.params.get(7).toString();

        StringBuilder sb = new StringBuilder();

        sb.append(String.format("get '%s', '%s'", tableName,
                rowKey));

        StringBuilder s = new StringBuilder();

        if (!(columnFamilyName.isEmpty() && qualifier.isEmpty())) {
            s.append((s.length() == 0) ? "" : ", ");
            if (!columnFamilyName.isEmpty() && !qualifier.isEmpty()) {
                s.append(String.format("COLUMN => '%s:%s'",
                        columnFamilyName, qualifier));
            } else {
                s.append(String.format("COLUMN => '%s'",
                        columnFamilyName.isEmpty() ? qualifier
                                : columnFamilyName));
            }
        }
        if (!consistency.isEmpty())
            s.append((s.length() == 0) ? "" : ", ")
                    .append("CONSISTENCY => '")
                    .append(consistency).append("'");
        if (!authorization.isEmpty()) {
            s.append((s.length() == 0) ? "" : ", ")
                    .append("AUTHORIZATIONS => ")
                    .append(authorization);
        }
        // if (!filter.isEmpty())
        // s.append((s.length() == 0) ? "" : ", ")
        // .append(filter);
        if (!regionReplicaID.isEmpty())
            s.append((s.length() == 0) ? "" : ", ")
                    .append("REGION_REPLICA_ID => ")
                    .append(regionReplicaID);
        if (!formatter.isEmpty())
            s.append((s.length() == 0) ? "" : ", ")
                    .append("FORMATTER => '")
                    .append(formatter).append("'");
        if ((s.length() == 0))
            return sb.toString();
        sb.append(", {").append(s.toString()).append("}");
        return sb.toString();
    }

    @Override
    public void updateState(State state) {

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