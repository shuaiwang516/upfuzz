echo "Checking CASSANDRA-18108 (base)"
echo "--------------------------------"

sum=0
cnt=0
need_time=0

for run in run1 run2 run3; do
  out="$(bin/check_cass_18108.sh artifact/bug-reproduction/trace/recorded_traces/cass-18108/base/$run/failure)"

  echo "$out"

  if echo "$out" | grep -q '^\[FAIL\]'; then
    sum="$(awk -v s="$sum" 'BEGIN{printf "%.2f", s+24.0}')"
    cnt=$((cnt+1))
  else
    # OK: parse "[Time] X hours"
    t="$(echo "$out" | awk '/^\[Time\]/{print $2; exit}')"
    if [ -z "$t" ]; then
      # 万一 OK 但没 Time，当 FAIL 处理
      t="24.0"
    fi
    sum="$(awk -v s="$sum" -v t="$t" 'BEGIN{printf "%.2f", s+t}')"
    cnt=$((cnt+1))
  fi
done

avg="$(awk -v s="$sum" -v c="$cnt" 'BEGIN{printf "%.2f", s/c}')"

echo "--------------------------------"
echo "[AVG] over $cnt runs: $avg hours"
