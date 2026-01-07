#!/bin/bash

echo -e "\033[1;34m▶ Running without DF...\033[0m"
bash artifact/overhead/hbase/hbase.sh false # without DF

echo -e "\033[1;35m▶ Running with DF...\033[0m"
bash artifact/overhead/hbase/hbase.sh true # with DF

echo -e "\033[1;32m✓ hbase 2.5.9 overhead:\033[0m"
bash artifact/overhead/hbase/compute.sh