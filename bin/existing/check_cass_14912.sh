#!/bin/bash
source bin/compute_time.sh

RESULT=$(grep -r "CommitLogReplayException" failure | grep "Exception follows: java.lang.AssertionError" | awk -F'/' '{print $2}' | uniq | sort -t '_' -k2,2n | head -n 1)

if [ -z "$RESULT" ]; then
    echo "bug is not triggered"
    exit
fi

# ls failure/$RESULT/fullSequence_*
DIR_NAME=failure/$RESULT
compute_triggering_time $DIR_NAME