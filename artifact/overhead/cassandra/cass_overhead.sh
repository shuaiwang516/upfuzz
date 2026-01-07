#!/bin/bash

# assert: current dir is ~/project/upfuzz
if [ "$PWD" != "$UPFUZZ_DIR" ]; then
  echo "Current directory is not ~/project/upfuzz"
  exit 1
fi

bash artifact/overhead/cassandra/cass_2.sh true # with DF
bash artifact/overhead/cassandra/cass_2.sh false # without DF

bash artifact/overhead/cassandra/compute.sh