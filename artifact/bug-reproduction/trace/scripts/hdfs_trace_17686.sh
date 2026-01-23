#!/bin/bash

# ================
# Usage: ./hdfs_trace.sh <CONFIG_MODE> <TEST_MODE>
#   CONFIG_MODE: "final" for df+vd+s, "base" for base
#   TEST_MODE: "dryrun" for test run (1 client), "large" for large test (12 clients)
# Example: ./hdfs_trace.sh final dryrun
#          ./hdfs_trace.sh base large
# ================

CONFIG_MODE="${1:-final}"
TEST_MODE="${2:-dryrun}"

echo "CONFIG_MODE: $CONFIG_MODE"
echo "TEST_MODE: $TEST_MODE"

# ================

# /proj/sosp21-upgrade-PG0/upfuzz_files/binary/hdfs

git checkout .
git pull

UPFUZZ_DIR=$PWD
ORI_VERSION=2.10.2
UP_VERSION=3.3.6

mkdir -p $UPFUZZ_DIR/prebuild/hdfs
cd $UPFUZZ_DIR/prebuild/hdfs
BIN_PATH=/proj/sosp21-upgrade-PG0/upfuzz_files/binary/hdfs/
FORMAT_BIN_PATH=/proj/sosp21-upgrade-PG0/upfuzz_files/format_inst_binary/hdfs/

rm -rf hadoop-$ORI_VERSION
rm -rf hadoop-$UP_VERSION

wget -q https://github.com/zlab-purdue/upfuzz/releases/download/inst/hadoop-2.10.2-INST-17219.tar.gz
tar -xzvf hadoop-2.10.2-INST-17219.tar.gz > /dev/null
wget -q https://github.com/zlab-purdue/upfuzz/releases/download/hadoop/hadoop-3.3.6.tar.gz
tar -xzvf hadoop-3.3.6.tar.gz > /dev/null

# old version hdfs daemon: 2.10.2
cp $UPFUZZ_DIR/src/main/resources/FsShellDaemon2.java $UPFUZZ_DIR/prebuild/hdfs/hadoop-"$ORI_VERSION"/FsShellDaemon.java
cd $UPFUZZ_DIR/prebuild/hdfs/hadoop-"$ORI_VERSION"/
/usr/lib/jvm/java-8-openjdk-amd64/bin/javac -d . -cp "share/hadoop/hdfs/*:share/hadoop/common/*:share/hadoop/common/lib/*" FsShellDaemon.java
sed -i "s/elif \[ \"\$COMMAND\" = \"dfs\" \] ; then/elif [ \"\$COMMAND\" = \"dfsdaemon\" ] ; then\n  CLASS=org.apache.hadoop.fs.FsShellDaemon\n  HADOOP_OPTS=\"\$HADOOP_OPTS \$HADOOP_CLIENT_OPTS\"\n&/" bin/hdfs

# new version hdfs daemon: 3.3.6
cp $UPFUZZ_DIR/src/main/resources/FsShellDaemon_trunk.java $UPFUZZ_DIR/prebuild/hdfs/hadoop-"$UP_VERSION"/FsShellDaemon.java
cd $UPFUZZ_DIR/prebuild/hdfs/hadoop-"$UP_VERSION"/
/usr/lib/jvm/java-8-openjdk-amd64/bin/javac -d . -cp "share/hadoop/hdfs/*:share/hadoop/common/*:share/hadoop/common/lib/*" FsShellDaemon.java
sed -i "s/  case \${subcmd} in/&\n    dfsdaemon)\n      HADOOP_CLASSNAME=\"org.apache.hadoop.fs.FsShellDaemon\"\n    ;;/" bin/hdfs

docker pull hanke580/upfuzz-ae:hdfs-${ORI_VERSION}_${UP_VERSION} > /dev/null
docker tag \
  hanke580/upfuzz-ae:hdfs-${ORI_VERSION}_${UP_VERSION} \
  upfuzz_hdfs:hadoop-${ORI_VERSION}_hadoop-${UP_VERSION}

# FC
cd $UPFUZZ_DIR
cp configInfo/hadoop-${ORI_VERSION}/* prebuild/hdfs/hadoop-${ORI_VERSION}/
cp lib/ssgFatJar.jar prebuild/hdfs/hadoop-${ORI_VERSION}/share/hadoop/common/lib/
cp lib/ssgFatJar.jar prebuild/hdfs/hadoop-${ORI_VERSION}/share/hadoop/hdfs/lib/

cd $UPFUZZ_DIR
./gradlew copyDependencies
./gradlew :spotlessApply build

# ===

git checkout .
git pull
./gradlew copyDependencies
./gradlew :spotlessApply build

# Select config based on CONFIG_MODE
if [ "$CONFIG_MODE" = "final" ]; then
  echo "Using final (df+vd+s) config"
  cp evaluation/new/HDFS-17686-config-format-vd-static.json hdfs_config.json
  diff evaluation/new/HDFS-17686-config-format-vd-static.json hdfs_config.json
elif [ "$CONFIG_MODE" = "base" ]; then
  echo "Using base config"
  cp evaluation/new/HDFS-17686-config-normal.json hdfs_config.json
  diff evaluation/new/HDFS-17686-config-normal.json hdfs_config.json
else
  echo "Unknown CONFIG_MODE: $CONFIG_MODE (use 'base' or 'final')"
  exit 1
fi

# Clean
cd $UPFUZZ_DIR; sudo chmod 777 /var/run/docker.sock; bin/clean.sh --force; bin/rm.sh; rm format_coverage.log

# ===

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
tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh hdfs_config.json > server.log' C-m \;
tmux send-keys -t 0:0.1 C-m "sleep 4; bin/start_clients.sh $NUM_CLIENTS hdfs_config.json" C-m