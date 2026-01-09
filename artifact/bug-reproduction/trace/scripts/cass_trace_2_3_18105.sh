cd ~/project/upfuzz
git checkout .
git pull

export UPFUZZ_DIR=$PWD
export ORI_VERSION=2.2.19
export UP_VERSION=3.0.30

mkdir -p prebuild/cassandra
cd prebuild/cassandra

rm -rf apache-cassandra-$ORI_VERSION
rm -rf apache-cassandra-$UP_VERSION

wget -q https://github.com/zlab-purdue/upfuzz/releases/download/inst/apache-cassandra-2.2.19-INST.tar.gz
tar -xzvf apache-cassandra-2.2.19-INST.tar.gz

wget -q https://github.com/zlab-purdue/upfuzz/releases/download/cassandra/apache-cassandra-3.0.30-bin.tar.gz
tar -xzvf apache-cassandra-3.0.30-bin.tar.gz

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

# == df+vd+s ==
cp evaluation/new/CASSANDRA-19623-config-format-vd-static.json config.json
diff evaluation/new/CASSANDRA-19623-config-format-vd-static.json config.json

# == base ==
# cp evaluation/new/CASSANDRA-19623-config-normal.json config.json
# diff evaluation/new/CASSANDRA-19623-config-normal.json config.json

# Clean
cd ~/project/upfuzz; sudo chmod 777 /var/run/docker.sock; bin/clean.sh --force; bin/rm.sh; rm format_coverage.log 

rm -rf ~/project/upfuzz/server.log

# =========

# Test run
tmux kill-session -t 0
tmux new-session -d -s 0 \; split-window -v \;
tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh config.json > server.log' C-m \;
tmux send-keys -t 0:0.1 C-m 'sleep 4; bin/start_clients.sh 1 config.json' C-m


# Large test
tmux kill-session -t 0
tmux new-session -d -s 0 \; split-window -v \;
tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh config.json > server.log' C-m \;
tmux send-keys -t 0:0.1 C-m 'sleep 4; bin/start_clients.sh 30 config.json' C-m
