# /proj/sosp21-upgrade-PG0/upfuzz_files/binary/hdfs

cd ~/project/upfuzz
git checkout .
git pull

cd ~/project/upfuzz
export UPFUZZ_DIR=$PWD
export ORI_VERSION=2.10.2
export UP_VERSION=3.3.6

mkdir -p $UPFUZZ_DIR/prebuild/hdfs
cd $UPFUZZ_DIR/prebuild/hdfs
BIN_PATH=/proj/sosp21-upgrade-PG0/upfuzz_files/binary/hdfs/
FORMAT_BIN_PATH=/proj/sosp21-upgrade-PG0/upfuzz_files/format_inst_binary/hdfs/

rm -rf hadoop-$ORI_VERSION
rm -rf hadoop-$UP_VERSION

# tar -xzvf $BIN_PATH/hadoop-$ORI_VERSION.tar.gz
# tar -xzvf $FORMAT_BIN_PATH/hadoop-$ORI_VERSION-INST.tar.gz
# 2.10.2: 17219
tar -xzvf $FORMAT_BIN_PATH/hadoop-2.10.2-INST-17219.tar.gz
tar -xzvf $BIN_PATH/hadoop-$UP_VERSION.tar.gz

# ===

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

cd $UPFUZZ_DIR/src/main/resources/hdfs/compile-src/
sed -i "s/ORG_VERSION=hadoop-.*$/ORG_VERSION=hadoop-$ORI_VERSION/" hdfs-clusternode.sh
sed -i "s/UPG_VERSION=hadoop-.*$/UPG_VERSION=hadoop-$UP_VERSION/" hdfs-clusternode.sh
docker build . -t upfuzz_hdfs:hadoop-"$ORI_VERSION"_hadoop-"$UP_VERSION"

# FC
cd $UPFUZZ_DIR
cp configInfo/hadoop-${ORI_VERSION}/* prebuild/hdfs/hadoop-${ORI_VERSION}/
cp lib/ssgFatJar.jar prebuild/hdfs/hadoop-${ORI_VERSION}/share/hadoop/common/lib/
cp lib/ssgFatJar.jar prebuild/hdfs/hadoop-${ORI_VERSION}/share/hadoop/hdfs/lib/

cd $UPFUZZ_DIR
./gradlew copyDependencies
./gradlew :spotlessApply build

# ===

cd ~/project/upfuzz
git checkout .
git pull
./gradlew copyDependencies
./gradlew :spotlessApply build

# == config ==

# VD-no-skip
# cp evaluation/HDFS-17219-config-format-vd-static-adjust-read-ratio-no-skip.json hdfs_config.json
# diff evaluation/HDFS-17219-config-format-vd-static-adjust-read-ratio-no-skip.json hdfs_config.json
# FC-no-skip
# cp evaluation/HDFS-17219-config-format1-adjust-read-ratio-no-skip.json hdfs_config.json
# diff evaluation/HDFS-17219-config-format1-adjust-read-ratio-no-skip.json hdfs_config.json

# ========

# == VD ==

# == BC ==

# Clean
cd ~/project/upfuzz; sudo chmod 777 /var/run/docker.sock; bin/clean.sh --force; bin/rm.sh; rm format_coverage.log

# ===

# Test run
# tmux new-session -d -s 0 \; split-window -v \;
# tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh hdfs_config.json > server.log' C-m \;
# tmux send-keys -t 0:0.1 C-m 'sleep 4; bin/start_clients.sh 1 hdfs_config.json' C-m

# no server.log
# tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh hdfs_config.json' C-m \;
# tmux send-keys -t 0:0.1 C-m 'sleep 4; bin/start_clients.sh 1 hdfs_config.json' C-m

# Large-scale test
tmux new-session -d -s 0 \; split-window -v \;
tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh hdfs_config.json > server.log' C-m \;
tmux send-keys -t 0:0.1 C-m 'sleep 4; bin/start_clients.sh 12 hdfs_config.json' C-m       