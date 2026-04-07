#!/usr/bin/env bash

HDFS="$HADOOP_HOME/bin/hdfs"

# Current Node Status
time=0
while true; do
    proc=`jps`
    echo "processes = $proc"
    if [[ $proc == *"NameNode"* || $proc == *"SecondaryNameNode"* || $proc == *"DataNode"* ]]; then
      echo "It's there!"
      time=$((time+1))
      echo "time = $time"
    fi
    if [[ $time -eq 3 ]]; then
      break
    fi
    sleep 5
done

# Connection to NN
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
