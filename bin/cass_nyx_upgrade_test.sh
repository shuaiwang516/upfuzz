#!/bin/bash

### Setup ssh for cloning upfuzz git repository
cd ~/upfuzz
git checkout -b implement_nyx

UPFUZZ_DIR=$PWD
ORI_VERSION=3.11.15
UP_VERSION=4.1.3
mkdir -p "$UPFUZZ_DIR"/prebuild/cassandra
cd ${UPFUZZ_DIR}/prebuild/cassandra
wget https://archive.apache.org/dist/cassandra/"$ORI_VERSION"/apache-cassandra-"$ORI_VERSION"-bin.tar.gz ; tar -xzvf apache-cassandra-"$ORI_VERSION"-bin.tar.gz
wget https://archive.apache.org/dist/cassandra/"$UP_VERSION"/apache-cassandra-"$UP_VERSION"-bin.tar.gz ; tar -xzvf apache-cassandra-"$UP_VERSION"-bin.tar.gz
sed -i 's/num_tokens: 16/num_tokens: 256/' apache-cassandra-"$UP_VERSION"/conf/cassandra.yaml

cd ${UPFUZZ_DIR}
cp src/main/resources/cqlsh_daemon2.py prebuild/cassandra/apache-cassandra-"$ORI_VERSION"/bin/cqlsh_daemon.py
cp src/main/resources/cqlsh_daemon3_4.0.5_4.1.0.py  prebuild/cassandra/apache-cassandra-"$UP_VERSION"/bin/cqlsh_daemon.py

cd ${UPFUZZ_DIR}/src/main/resources/cassandra/upgrade-testing/compile-src/
sed -i "s/ORI_VERSION=apache-cassandra-.*/ORI_VERSION=apache-cassandra-${ORI_VERSION}/" cassandra-clusternode.sh
sed -i "s/UPG_VERSION=apache-cassandra-.*/UPG_VERSION=apache-cassandra-${UP_VERSION}/" cassandra-clusternode.sh
docker build . -t upfuzz_cassandra:apache-cassandra-"$ORI_VERSION"_apache-cassandra-"$UP_VERSION"

cd ${UPFUZZ_DIR}
./gradlew copyDependencies
./gradlew :spotlessApply build
