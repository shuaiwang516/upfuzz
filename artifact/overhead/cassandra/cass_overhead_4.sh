#!/bin/bash

echo -e "\033[1;34m▶ Running without DF...\033[0m"
bash artifact/overhead/cassandra/cass_4.sh false # without DF

echo -e "\033[1;34m▶ Running with DF...\033[0m"
bash artifact/overhead/cassandra/cass_4.sh true # with DF

echo -e "\033[1;32m✓ cassandra 4.1.6 overhead:\033[0m"
bash artifact/overhead/compute.sh