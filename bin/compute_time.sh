#!/bin/bash

# Input: failure/failure_xxx
# Output: print triggering time

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m'   # no color

compute_triggering_time() {
    # Check if a directory argument was provided
    if [ -z "$1" ]; then
        echo "Usage: list_dir_contents <directory>"
        return 1
    fi

    # Assign the directory path to a variable
    local dir="$1"

    # Check if the provided argument is a valid directory
    if [ ! -d "$dir" ]; then
        echo "Error: $dir is not a valid directory."
        return 1
    fi

    # List everything inside the directory
    FILENAME=$(ls $dir/fullSequence_*)
    BASENAME=$(basename "$FILENAME")
    NUM="${BASENAME#fullSequence_}"
    NUM="${NUM%.report}"
    HOURS=$(echo "scale=2; $NUM / 3600" | bc)

    # Output the results
    # echo "Bug Report: $FILENAME"
    echo -e "${GREEN}[Time]${NC} $HOURS hours"
}