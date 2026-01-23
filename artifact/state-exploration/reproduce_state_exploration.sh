#! /bin/bash

cd artifact/state-exploration
wget -q https://github.com/zlab-purdue/upfuzz/releases/download/v1.0-data/state-exploration-data.tar.gz
tar -xzvf state-exploration-data.tar.gz > /dev/null
cd state-exploration-data
python3 ../run.py > /dev/null 2>&1

echo "------------ state exploration --------------------"
# all.pdf will be generated at path artifact/state-exploration/all.pdf
echo "State exploration figure is generated at artifact/state-exploration/state-exploration-data/all.pdf"
echo "Full path of the figure: "
realpath all.pdf
echo "------------ state exploration --------------------"
