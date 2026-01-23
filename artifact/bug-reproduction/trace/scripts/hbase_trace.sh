#!/bin/bash

# ================
# Usage: ./hbase_trace.sh <CONFIG_MODE> <TEST_MODE>
#   CONFIG_MODE: "final" for df+vd+s, "base" for base
#   TEST_MODE: "dryrun" for test run (1 client), "large" for large test (12 clients)
# Example: ./hbase_trace.sh final dryrun
#          ./hbase_trace.sh base large
# ================

CONFIG_MODE="${1:-final}"
TEST_MODE="${2:-dryrun}"

echo "CONFIG_MODE: $CONFIG_MODE"
echo "TEST_MODE: $TEST_MODE"

# ================

# Binary path
# ls /proj/sosp21-upgrade-PG0/upfuzz_files/binary/hbase
# ls /proj/sosp21-upgrade-PG0/upfuzz_files/format_inst_binary/hbase

# ===

git checkout .
git pull

UPFUZZ_DIR=$PWD
ORI_VERSION=2.5.9
UP_VERSION=3.0.0

mkdir -p $UPFUZZ_DIR/prebuild/hadoop
cd $UPFUZZ_DIR/prebuild/hadoop
if [ ! -d "hadoop-2.10.2" ]; then
  sudo rm -rf hadoop-2.10.2
  wget -q https://github.com/zlab-purdue/upfuzz/releases/download/hadoop/hadoop-2.10.2.tar.gz
  tar -xzvf hadoop-2.10.2.tar.gz > /dev/null
  cp $UPFUZZ_DIR/src/main/resources/hdfs/hbase-pure/core-site.xml $UPFUZZ_DIR/prebuild/hadoop/hadoop-2.10.2/etc/hadoop/ -f
  cp $UPFUZZ_DIR/src/main/resources/hdfs/hbase-pure/hdfs-site.xml $UPFUZZ_DIR/prebuild/hadoop/hadoop-2.10.2/etc/hadoop/ -f
  cp $UPFUZZ_DIR/src/main/resources/hdfs/hbase-pure/hadoop-env.sh $UPFUZZ_DIR/prebuild/hadoop/hadoop-2.10.2/etc/hadoop/ -f
fi

mkdir -p $UPFUZZ_DIR/prebuild/hbase
cd $UPFUZZ_DIR/prebuild/hbase

sudo rm -rf hbase-$ORI_VERSION
sudo rm -rf hbase-$UP_VERSION

wget -q https://github.com/zlab-purdue/upfuzz/releases/download/inst/hbase-2.5.9-bin-INST.tar.gz
tar -xzvf hbase-2.5.9-bin-INST.tar.gz > /dev/null

wget -q https://github.com/zlab-purdue/upfuzz/releases/download/hbase/hbase-3.0.0-516c89e8597fb6-bin.tar.gz
tar -xzvf hbase-3.0.0-516c89e8597fb6-bin.tar.gz > /dev/null

cp $UPFUZZ_DIR/src/main/resources/hbase/compile-src/hbase-env.sh $UPFUZZ_DIR/prebuild/hbase/hbase-$ORI_VERSION/conf/ -f
cp $UPFUZZ_DIR/src/main/resources/hbase/compile-src/hbase-env.sh $UPFUZZ_DIR/prebuild/hbase/hbase-$UP_VERSION/conf/ -f

# If testing 3.0.0
cp $UPFUZZ_DIR/src/main/resources/hbase/compile-src/hbase-env-jdk17.sh $UPFUZZ_DIR/prebuild/hbase/hbase-$UP_VERSION/conf/hbase-env.sh -f

# for hbase version >= 2.4.0, use hbase_daemon3.py
# for hbase version < 2.4.0, use hbase_daemon2.py
cp $UPFUZZ_DIR/src/main/resources/hbase/compile-src/hbase_daemon3.py $UPFUZZ_DIR/prebuild/hbase/hbase-$ORI_VERSION/bin/hbase_daemon.py
cp $UPFUZZ_DIR/src/main/resources/hbase/compile-src/hbase_daemon3.py $UPFUZZ_DIR/prebuild/hbase/hbase-$UP_VERSION/bin/hbase_daemon.py

# FC: Use global info analysis results
cd $UPFUZZ_DIR
cp configInfo/hbase-2.5.9-global/* configInfo/hbase-2.5.9/
cp configInfo/hbase-3.0.0-global/* configInfo/hbase-3.0.0/

# == FC ==
cd $UPFUZZ_DIR
cp configInfo/hbase-${ORI_VERSION}/* prebuild/hbase/hbase-${ORI_VERSION}/
cp lib/ssgFatJar.jar prebuild/hbase/hbase-${ORI_VERSION}/lib/
# == FC ==

# == Build images ==
# cd $UPFUZZ_DIR/src/main/resources/hdfs/hbase-pure/
# docker build . -t upfuzz_hdfs:hadoop-2.10.2
# cd $UPFUZZ_DIR/src/main/resources/hbase/compile-src/
# docker build . -t upfuzz_hbase:hbase-"$ORI_VERSION"_hbase-"$UP_VERSION"
# == Build images ==

docker pull hanke580/upfuzz-ae:hbase-${ORI_VERSION}_${UP_VERSION} > /dev/null
docker tag \
hanke580/upfuzz-ae:hbase-${ORI_VERSION}_${UP_VERSION} \
upfuzz_hbase:hbase-${ORI_VERSION}_hbase-${UP_VERSION}

docker pull hanke580/upfuzz-ae:hdfs-2.10.2 > /dev/null
docker tag hanke580/upfuzz-ae:hdfs-2.10.2 \
upfuzz_hdfs:hadoop-2.10.2

cd $UPFUZZ_DIR
./gradlew copyDependencies
./gradlew :spotlessApply build

# ======

git checkout .
git pull
./gradlew copyDependencies
./gradlew :spotlessApply build

# Select config based on CONFIG_MODE
if [ "$CONFIG_MODE" = "final" ]; then
  echo "Using final (df+vd+s) config"
  cp evaluation/new/HBASE-28583-config-format-vd-static-no-skip.json hbase_config.json
  diff evaluation/new/HBASE-28583-config-format-vd-static-no-skip.json hbase_config.json
elif [ "$CONFIG_MODE" = "base" ]; then
  echo "Using base config"
  cp evaluation/new/HBASE-28583-config-normal.json hbase_config.json
  diff evaluation/new/HBASE-28583-config-normal.json hbase_config.json
else
  echo "Unknown CONFIG_MODE: $CONFIG_MODE (use 'base' or 'final')"
  exit 1
fi

# Clean
cd $UPFUZZ_DIR; sudo chmod 777 /var/run/docker.sock; bin/clean.sh --force; bin/rm.sh; rm format_coverage.log 

# Select number of clients based on TEST_MODE
if [ "$TEST_MODE" = "dryrun" ]; then
  NUM_CLIENTS=1
elif [ "$TEST_MODE" = "large" ]; then
  NUM_CLIENTS=12
else
  echo "Unknown TEST_MODE: $TEST_MODE (use 'dryrun' or 'large')"
  exit 1
fi

echo "Running with $NUM_CLIENTS client(s)"

tmux kill-session -t 0
tmux new-session -d -s 0 \; split-window -v \;
tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh hbase_config.json > server.log' C-m \;
tmux send-keys -t 0:0.1 C-m "sleep 4; bin/start_clients.sh $NUM_CLIENTS hbase_config.json" C-m
