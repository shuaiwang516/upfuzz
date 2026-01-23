#!/bin/bash

UPFUZZ_DIR=$PWD

cd artifact/bug-reproduction/trace
echo "Downloading pre-recorded traces..."
wget -q https://github.com/zlab-purdue/upfuzz/releases/download/trace/recorded_traces.tar.gz; tar -xzvf recorded_traces.tar.gz > /dev/null;

cd $UPFUZZ_DIR
echo "Computing average triggering time for all bugs..."
bash artifact/bug-reproduction/trace/scripts/compute_all_bugs.sh > results.txt; 

echo "Bug Triggering Time are saved in results.txt"
realpath results.txt