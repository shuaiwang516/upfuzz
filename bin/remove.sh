#!/bin/bash

# remove all upfuzz related files and pull the latest code

bin/clean.sh; bin/rm.sh

rm -rf /tmp/upfuzz 
sudo rm -rf prebuild

git checkout .
git pull