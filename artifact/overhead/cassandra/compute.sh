#!/bin/bash

# assert that client_normal.log and client_format.log exist
if [ ! -f "client_normal.log" ]; then
  echo "client_normal.log does not exist"
  exit 1
fi
if [ ! -f "client_format.log" ]; then
  echo "client_format.log does not exist"
  exit 1
fi

t1_normal=$(grep "Connect to cqlsh" client_normal.log | head -n 1 | awk '{print $2}')
t2_normal=$(grep "Cqlsh connected" client_normal.log | head -n 1 | awk '{print $2}')
t3_normal=$(grep "collect coverage" client_normal.log | head -n 1 | awk '{print $2}')

t1_format=$(grep "Connect to cqlsh" client_format.log | head -n 1 | awk '{print $2}')
t2_format=$(grep "Cqlsh connected" client_format.log | head -n 1 | awk '{print $2}')
t3_format=$(grep "collect coverage" client_format.log | head -n 1 | awk '{print $2}')

to_ms () {
  local t=$1
  IFS=':,' read -r h m s ms <<< "$t"
  # Remove leading zeros to avoid octal interpretation
  h=$((10#$h)); m=$((10#$m)); s=$((10#$s)); ms=$((10#$ms))
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

overhead1=$(awk "BEGIN {printf \"%.4f\", ($interval_1_2_ms_format - $interval_1_2_ms_normal) / $interval_1_2_ms_normal}")
overhead2=$(awk "BEGIN {printf \"%.4f\", ($interval_2_3_ms_format - $interval_2_3_ms_normal) / $interval_2_3_ms_normal}")

overhead1_pct=$(awk "BEGIN {printf \"%.2f\", $overhead1 * 100}")
overhead2_pct=$(awk "BEGIN {printf \"%.2f\", $overhead2 * 100}")

echo "Overhead 1: ${overhead1_pct}%"
echo "Overhead 2: ${overhead2_pct}%"

