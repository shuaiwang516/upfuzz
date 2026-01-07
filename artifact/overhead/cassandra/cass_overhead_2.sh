#!/bin/bash

echo "Running without DF..."
bash artifact/overhead/cassandra/cass_2.sh false # without DF

echo "Running with DF..."
bash artifact/overhead/cassandra/cass_2.sh true # with DF

echo "cassandra 2.2.19 overhead:"
bash artifact/overhead/cassandra/compute.sh