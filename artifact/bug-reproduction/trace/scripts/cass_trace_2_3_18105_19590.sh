#!/bin/bash

# ================
# Usage: ./cass_trace_2_3_18105.sh <CONFIG_MODE> <TEST_MODE>
#   CONFIG_MODE: "final" for df+vd+s, "base" for base
#   TEST_MODE: "dryrun" for test run (1 client), "large" for large test (30 clients)
# Example: ./cass_trace_2_3_18105.sh final dryrun
#          ./cass_trace_2_3_18105.sh base large
# ================

CONFIG_MODE="${1:-final}"
TEST_MODE="${2:-dryrun}"

echo "CONFIG_MODE: $CONFIG_MODE"
echo "TEST_MODE: $TEST_MODE"

# ================

git checkout .
git pull

UPFUZZ_DIR=$PWD
ORI_VERSION=2.2.19
UP_VERSION=3.0.30

mkdir -p prebuild/cassandra
cd prebuild/cassandra

rm -rf apache-cassandra-$ORI_VERSION
rm -rf apache-cassandra-$UP_VERSION

wget -q https://github.com/zlab-purdue/upfuzz/releases/download/inst/apache-cassandra-2.2.19-INST.tar.gz
tar -xzvf apache-cassandra-2.2.19-INST.tar.gz > /dev/null

wget -q https://github.com/zlab-purdue/upfuzz/releases/download/cassandra/apache-cassandra-3.0.30-bin.tar.gz
tar -xzvf apache-cassandra-3.0.30-bin.tar.gz > /dev/null

cd ${UPFUZZ_DIR}
cp configInfo/apache-cassandra-${ORI_VERSION}/* prebuild/cassandra/apache-cassandra-${ORI_VERSION}
cp lib/ssgFatJar.jar prebuild/cassandra/apache-cassandra-${ORI_VERSION}/lib/ssgFatJar.jar

cd ${UPFUZZ_DIR}
cp src/main/resources/cqlsh_daemon2.py prebuild/cassandra/apache-cassandra-"$ORI_VERSION"/bin/cqlsh_daemon.py
cp src/main/resources/cqlsh_daemon2.py  prebuild/cassandra/apache-cassandra-"$UP_VERSION"/bin/cqlsh_daemon.py

docker pull hanke580/upfuzz-ae:cassandra-${ORI_VERSION}_${UP_VERSION} > /dev/null
docker tag \
  hanke580/upfuzz-ae:cassandra-${ORI_VERSION}_${UP_VERSION} \
  upfuzz_cassandra:apache-cassandra-${ORI_VERSION}_apache-cassandra-${UP_VERSION}

cd ${UPFUZZ_DIR}
./gradlew copyDependencies
./gradlew :spotlessApply build

cd ${UPFUZZ_DIR}

# ================
git pull

# Select config based on CONFIG_MODE
if [ "$CONFIG_MODE" = "final" ]; then
  echo "Using final (df+vd+s) config"
  cp evaluation/new/CASSANDRA-19623-config-format-vd-static.json config.json
  diff evaluation/new/CASSANDRA-19623-config-format-vd-static.json config.json
elif [ "$CONFIG_MODE" = "base" ]; then
  echo "Using base config"
  cp evaluation/new/CASSANDRA-19623-config-normal.json config.json
  diff evaluation/new/CASSANDRA-19623-config-normal.json config.json
else
  echo "Unknown CONFIG_MODE: $CONFIG_MODE (use 'base' or 'final')"
  exit 1
fi

# Clean
cd $UPFUZZ_DIR; sudo chmod 777 /var/run/docker.sock; bin/clean.sh --force; bin/rm.sh; rm format_coverage.log 

rm -rf $UPFUZZ_DIR/server.log

# =========

# Select number of clients based on TEST_MODE
if [ "$TEST_MODE" = "dryrun" ]; then
  NUM_CLIENTS=1
elif [ "$TEST_MODE" = "large" ]; then
  NUM_CLIENTS=30
else
  echo "Unknown TEST_MODE: $TEST_MODE (use 'dryrun' or 'large')"
  exit 1
fi

echo "Running with $NUM_CLIENTS client(s)"

tmux kill-session -t 0
tmux new-session -d -s 0 \; split-window -v \;
tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh config.json > server.log' C-m \;
tmux send-keys -t 0:0.1 C-m "sleep 4; bin/start_clients.sh $NUM_CLIENTS config.json" C-m
