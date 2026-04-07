#!/usr/bin/env bash

HDFS="$HADOOP_HOME/bin/hdfs"

# TODO: Add similar jps check bug for Secur data node

while true; do
    $HDFS dfs -ls /
    if [[ "$?" -eq 0 ]];
    then
        break
    fi
    sleep 5
done

export NET_TRACE_NODE_ROLE=client
export HADOOP_CLIENT_OPTS="${HADOOP_CLIENT_OPTS} -DNET_TRACE_NODE_ROLE=client"
$HDFS dfsdaemon
