# !/bin/bash

mkdir -p /tmp/upfuzz/hdfs
rm -rf /tmp/upfuzz/hdfs/GUBIkxOc
cp -r artifact/bug-reproduction/bugs/HDFS-16984/GUBIkxOc /tmp/upfuzz/hdfs/
bash artifact/bug-reproduction/hdfs_repo.sh 16984 false