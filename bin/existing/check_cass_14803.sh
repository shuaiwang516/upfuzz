#!/bin/bash
source bin/compute_time.sh

# Step 1: Run the bin/check_cass_14803.sh script and capture its output
OUTPUT=$(bin/find_failure_with_DESC.sh; cat input_file | bin/grep_and_newline.sh | grep -i -m2 -B 4 "found")

if [ -z "$OUTPUT" ]; then
    echo "Bug is not triggered yet"
    exit 0
fi

# Step 2: Extract the first report's file path
FIRST_FILE=$(echo "$OUTPUT" | grep -m1 "Processing file:" | awk '{print $3}')

# Step 3: Extract the directory name up to "failure/failure_N/"
DIR_NAME=$(echo "$FIRST_FILE" | sed -E 's|(failure/failure_[^/]+)/.*|\1|')

# Check if DIR_NAME was successfully extracted
if [ -z "$DIR_NAME" ]; then
    echo "Could not extract the directory name from the first report."
    exit 1
fi

compute_triggering_time $DIR_NAME


# Step 4: List all files inside the directory
# echo "Listing all files inside $DIR_NAME:"
# ls $DIR_NAME/fullSequence_*


