#!/bin/bash

UPFUZZ_DIR="~/project/upfuzz"

# Check for the required arguments
if [ "$#" -lt 3 ]; then
    echo "Usage: $0 <start> <end> <server_prefix>"
    exit 1
fi

# Read arguments
start=$1       # First argument: starting index, inclusive
end=$2         # Second argument: ending index, inclusive
server_prefix=$3

for n in $(seq $start $end); do
    server="${server_prefix}${n}"
		ssh $server "cd $UPFUZZ_DIR && grep \"new combinations\" format_coverage.log | wc -l "

done

echo "Done."
