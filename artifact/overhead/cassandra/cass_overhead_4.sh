#!/bin/bash

echo "Running without DF..."
bash artifact/overhead/cassandra/cass_4.sh false # without DF

echo "Running with DF..."
bash artifact/overhead/cassandra/cass_4.sh true # with DF

echo "cassandra 4.1.6 overhead:"
bash artifact/overhead/cassandra/compute.sh