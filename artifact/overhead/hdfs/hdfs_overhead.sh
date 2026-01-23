#!/bin/bash

echo -e "\033[1;34m▶ Running without DF...\033[0m"
bash artifact/overhead/hdfs/hdfs.sh false # without DF

echo -e "\033[1;34m▶ Running with DF...\033[0m"
bash artifact/overhead/hdfs/hdfs.sh true # with DF

echo -e "\033[1;32m✓ hdfs 2.10.2 overhead:\033[0m"
bash artifact/overhead/compute.sh