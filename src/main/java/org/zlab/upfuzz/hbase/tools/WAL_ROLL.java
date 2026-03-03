package org.zlab.upfuzz.hbase.tools;

import org.zlab.upfuzz.Parameter;
import org.zlab.upfuzz.ParameterType;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.hbase.HBaseCommand;
import org.zlab.upfuzz.hbase.HBaseState;
import org.zlab.upfuzz.utils.BOOLType;
import org.zlab.upfuzz.utils.CONSTANTSTRINGType;
import org.zlab.upfuzz.utils.Utilities;

public class WAL_ROLL extends HBaseCommand {
    public WAL_ROLL(HBaseState state) {
        super(state);
// select from region servers
        Parameter regionserver = new ParameterType.InCollectionType(
                CONSTANTSTRINGType.instance,
                (s, c) -> Utilities
                        .strings2Parameters(
                                Config.getConf().getHBaseRegionServers()),
                null).generateRandomParameter(state, this);
        params.add(regionserver); // 0 regionserver
    }

    @Override
    public String constructCommandString() {
        String regionserver = String.format("'%s,%d'", params.get(0).toString(),
                Config.getConf().REGIONSERVER_PORT);
        return "wal_roll " + regionserver;
    }

    @Override
    public void updateState(State state) {
    }
}
