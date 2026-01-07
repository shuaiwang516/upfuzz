#!/bin/bash

t1_normal=$(grep "Connect to cqlsh" client_normal.log | head -n 1 | awk '{print $2}')
t2_normal=$(grep "Cqlsh connected" client_normal.log | head -n 1 | awk '{print $2}')
t3_normal=$(grep "collect coverage" client_normal.log | head -n 1 | awk '{print $2}')

t1_format=$(grep "Connect to cqlsh" client_format.log | head -n 1 | awk '{print $2}')
t2_format=$(grep "Cqlsh connected" client_format.log | head -n 1 | awk '{print $2}')
t3_format=$(grep "collect coverage" client_format.log | head -n 1 | awk '{print $2}')

to_ms () {
  local t=$1
  IFS=':,' read -r h m s ms <<< "$t"
  echo $(( (h*3600 + m*60 + s)*1000 + ms ))
}

ms1_normal=$(to_ms "$t1_normal")
ms2_normal=$(to_ms "$t2_normal")
ms3_normal=$(to_ms "$t3_normal")

ms1_format=$(to_ms "$t1_format")
ms2_format=$(to_ms "$t2_format")
ms3_format=$(to_ms "$t3_format")

interval_1_2_ms_normal=$((ms2_normal - ms1_normal))
interval_2_3_ms_normal=$((ms3_normal - ms2_normal))

interval_1_2_ms_format=$((ms2_format - ms1_format))
interval_2_3_ms_format=$((ms3_format - ms2_format))

# compute overhead: (interval_1_2_ms_format-interval_1_2_ms_normal) / interval_1_2_ms_normal

overhead1 = $((interval_1_2_ms_format - interval_1_2_ms_normal) / interval_1_2_ms_normal)
overhead2 = $((interval_2_3_ms_format - interval_2_3_ms_normal) / interval_2_3_ms_normal)

echo "Overhead 1: $((interval_1_2_ms_format - interval_1_2_ms_normal) / interval_1_2_ms_normal)"
echo "Overhead 2: $((interval_2_3_ms_format - interval_2_3_ms_normal) / interval_2_3_ms_normal)"

