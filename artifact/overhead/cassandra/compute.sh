#!/bin/bash

t1=$(grep "Connect to cqlsh" client.log | head -n 1 | awk '{print $2}')
t2=$(grep "Cqlsh connected" client.log | head -n 1 | awk '{print $2}')
t3=$(grep "collect coverage" client.log | head -n 1 | awk '{print $2}')

to_ms () {
  local t=$1
  IFS=':,' read -r h m s ms <<< "$t"
  echo $(( (h*3600 + m*60 + s)*1000 + ms ))
}

ms1=$(to_ms "$t1")
ms2=$(to_ms "$t2")
ms3=$(to_ms "$t3")

interval_1_2_ms=$((ms2 - ms1))
interval_2_3_ms=$((ms3 - ms2))

interval_1_2_s=$(awk -v x=$interval_1_2_ms 'BEGIN{printf "%.3f", x/1000}')
interval_2_3_s=$(awk -v x=$interval_2_3_ms 'BEGIN{printf "%.3f", x/1000}')

echo "t1 -> t2 = ${interval_1_2_s}s"
echo "t2 -> t3 = ${interval_2_3_s}s"
