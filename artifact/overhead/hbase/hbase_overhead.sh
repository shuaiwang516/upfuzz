#!/bin/bash

echo "Running without DF..."
bash artifact/overhead/hbase/hbase.sh false # without DF

echo "Running with DF..."
bash artifact/overhead/hbase/hbase.sh true # with DF

echo "hbase 2.5.9 overhead:"
bash artifact/overhead/hbase/compute.sh