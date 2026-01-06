#!/bin/bash
cd ~/project/upfuzz
export UPFUZZ_DIR=$PWD
export ORI_VERSION=3.11.17
export UP_VERSION=4.1.4

mkdir -p "$UPFUZZ_DIR"/prebuild/cassandra
cd prebuild/cassandra
wget https://archive.apache.org/dist/cassandra/"$ORI_VERSION"/apache-cassandra-"$ORI_VERSION"-bin.tar.gz ; tar -xzvf apache-cassandra-"$ORI_VERSION"-bin.tar.gz
wget https://archive.apache.org/dist/cassandra/"$UP_VERSION"/apache-cassandra-"$UP_VERSION"-bin.tar.gz ; tar -xzvf apache-cassandra-"$UP_VERSION"-bin.tar.gz
sed -i 's/num_tokens: 16/num_tokens: 256/' apache-cassandra-"$UP_VERSION"/conf/cassandra.yaml

cd ${UPFUZZ_DIR}
cp src/main/resources/cqlsh_daemon2.py prebuild/cassandra/apache-cassandra-"$ORI_VERSION"/bin/cqlsh_daemon.py
cp src/main/resources/cqlsh_daemon4.py  prebuild/cassandra/apache-cassandra-"$UP_VERSION"/bin/cqlsh_daemon.py

docker pull hanke580/upfuzz-ae:cassandra-3.11.17_4.1.4
docker tag \
  hanke580/upfuzz-ae:cassandra-3.11.17_4.1.4 \
  upfuzz_cassandra:apache-cassandra-3.11.17_apache-cassandra-4.1.4

cd ${UPFUZZ_DIR}
./gradlew copyDependencies
./gradlew :spotlessApply build

# Create session + Test run
tmux kill-session -t 0
tmux new-session -d -s 0 \; split-window -v \;
tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh config.json > server.log' C-m \;
tmux send-keys -t 0:0.1 C-m 'sleep 2; bin/start_clients.sh 1 config.json > client.log' C-m