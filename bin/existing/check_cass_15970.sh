#!/bin/bash
source bin/compute_time.sh

DIR_NAME=$(grep -rl "CommitLogReplayException" failure | sort -t_ -k2,2n | head -n1 | awk -F'/' '{print $1 "/" $2}')

if [[ -z "$DIR_NAME" ]]; then
  echo -e "\e[31mBug is not triggered yet\e[0m" 
  exit 1
fi

compute_triggering_time $DIR_NAME
