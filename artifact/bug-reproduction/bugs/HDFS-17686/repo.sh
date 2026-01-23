# !/bin/bash

mkdir -p /tmp/upfuzz/hdfs
rm -rf /tmp/upfuzz/hdfs/pVfezjff
cp -r artifact/bug-reproduction/bugs/HDFS-17686/pVfezjff /tmp/upfuzz/hdfs/
bash artifact/bug-reproduction/hdfs_repo.sh 17686 false