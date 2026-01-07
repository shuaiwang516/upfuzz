# Binary path
ls /proj/sosp21-upgrade-PG0/upfuzz_files/binary/hbase
ls /proj/sosp21-upgrade-PG0/upfuzz_files/format_inst_binary/hbase

# ===

cd ~/project/upfuzz
git checkout .
git pull

cd ~/project/upfuzz
export UPFUZZ_DIR=$PWD
export ORI_VERSION=2.5.9
export UP_VERSION=3.0.0

mkdir -p $UPFUZZ_DIR/prebuild/hadoop
cd $UPFUZZ_DIR/prebuild/hadoop
sudo rm -rf hadoop-2.10.2
HDFS_BIN_PATH=/proj/sosp21-upgrade-PG0/upfuzz_files/binary/hdfs/
tar -xzvf $HDFS_BIN_PATH/hadoop-2.10.2.tar.gz
cp $UPFUZZ_DIR/src/main/resources/hdfs/hbase-pure/core-site.xml $UPFUZZ_DIR/prebuild/hadoop/hadoop-2.10.2/etc/hadoop/ -f
cp $UPFUZZ_DIR/src/main/resources/hdfs/hbase-pure/hdfs-site.xml $UPFUZZ_DIR/prebuild/hadoop/hadoop-2.10.2/etc/hadoop/ -f
cp $UPFUZZ_DIR/src/main/resources/hdfs/hbase-pure/hadoop-env.sh $UPFUZZ_DIR/prebuild/hadoop/hadoop-2.10.2/etc/hadoop/ -f

mkdir -p $UPFUZZ_DIR/prebuild/hbase
cd $UPFUZZ_DIR/prebuild/hbase

sudo rm -rf hbase-$ORI_VERSION
sudo rm -rf hbase-$UP_VERSION

HBASE_BIN_PATH=/proj/sosp21-upgrade-PG0/upfuzz_files/binary/hbase/
HBASE_FORMAT_BIN_PATH=/proj/sosp21-upgrade-PG0/upfuzz_files/format_inst_binary/hbase/

# tar -xzvf $HBASE_BIN_PATH/hbase-"$ORI_VERSION".tar.gz
# tar -xzvf $HBASE_FORMAT_BIN_PATH/hbase-"$ORI_VERSION"-bin-INST.tar.gz
tar -xzvf $HBASE_FORMAT_BIN_PATH/hbase-"$ORI_VERSION"-bin-INST-global.tar.gz
# tar -xzvf $HBASE_BIN_PATH/hbase-"$UP_VERSION".tar.gz
tar -xzvf $HBASE_BIN_PATH/hbase-"$UP_VERSION"-516c89e8597fb6-bin.tar.gz 

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

# FC
cd $UPFUZZ_DIR
cp configInfo/hbase-${ORI_VERSION}/* prebuild/hbase/hbase-${ORI_VERSION}/
cp lib/ssgFatJar.jar prebuild/hbase/hbase-${ORI_VERSION}/lib/

# cd $UPFUZZ_DIR/src/main/resources/hdfs/hbase-pure/
# docker build . -t upfuzz_hdfs:hadoop-2.10.2

# cd $UPFUZZ_DIR/src/main/resources/hbase/compile-src/
# docker build . -t upfuzz_hbase:hbase-"$ORI_VERSION"_hbase-"$UP_VERSION"

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

cd ~/project/upfuzz
git checkout .
git pull
./gradlew copyDependencies
./gradlew :spotlessApply build

# == VD ==
cp evaluation/new/HBASE-28583-config-format-vd-static-no-skip.json hbase_config.json
diff evaluation/new/HBASE-28583-config-format-vd-static-no-skip.json hbase_config.json

# == BC ==
# cp evaluation/new/HBASE-28583-config-normal.json hbase_config.json
# diff evaluation/new/HBASE-28583-config-normal.json hbase_config.json

# Clean
cd ~/project/upfuzz; sudo chmod 777 /var/run/docker.sock; bin/clean.sh; bin/rm.sh; rm format_coverage.log 

# Test run
tmux new-session -d -s 0 \; split-window -v \;
tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh hbase_config.json > server.log' C-m \;
tmux send-keys -t 0:0.1 C-m 'sleep 4; bin/start_clients.sh 1 hbase_config.json' C-m

# Larget-scale Test
tmux new-session -d -s 0 \; split-window -v \;
tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh hbase_config.json > server.log' C-m \;
tmux send-keys -t 0:0.1 C-m 'sleep 4; bin/start_clients.sh 12 hbase_config.json' C-m