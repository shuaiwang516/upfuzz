#!/bin/bash

### Setup ssh for cloning upfuzz git repository
cd ~/upfuzz
git checkout -b implement_nyx

UPFUZZ_DIR=$PWD
ORI_VERSION=3.11.15

mkdir -p ${UPFUZZ_DIR}/prebuild/cassandra
cd ${UPFUZZ_DIR}/prebuild/cassandra
wget https://archive.apache.org/dist/cassandra/"$ORI_VERSION"/apache-cassandra-"$ORI_VERSION"-bin.tar.gz ; tar -xzvf apache-cassandra-"$ORI_VERSION"-bin.tar.gz

cd ${UPFUZZ_DIR}
cp src/main/resources/cqlsh_daemon2.py prebuild/cassandra/apache-cassandra-"$ORI_VERSION"/bin/cqlsh_daemon.py

cd ${UPFUZZ_DIR}/src/main/resources/cassandra/single-version-testing
sed -i "s/ORI_VERSION=apache-cassandra-.*/ORI_VERSION=apache-cassandra-${ORI_VERSION}/" cassandra-clusternode.sh
docker build . -t upfuzz_cassandra:apache-cassandra-"$ORI_VERSION"

cd ${UPFUZZ_DIR}
./gradlew copyDependencies
./gradlew :spotlessApply build
