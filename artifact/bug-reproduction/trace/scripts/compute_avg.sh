echo "Checking CASSANDRA-18108 (base)"
echo "--------------------------------"

sum=0
cnt=0
need_time=0

if [ -z "$1" ] || [ -z "$2" ] || [ -z "$3" ]; then
    echo "Usage: $0 <check_script> <bug_dir> <mode>"
    echo "mode: base or df_vd_s"
    echo "Example: $0 bin/check_cass_18108.sh cass-18108 base"
    echo "Example: $0 bin/check_cass_18108.sh cass-18108 df_vd_s"
    exit 1
fi

CHECK_SCRIPT="$1"
BUG_DIR="$2"

MODE="base"
if [ "$3" == "df_vd_s" ]; then
    MODE="df_vd_s"
fi

for run in run1 run2 run3; do
  out="$($CHECK_SCRIPT artifact/bug-reproduction/trace/recorded_traces/$BUG_DIR/$MODE/$run/failure)"
  echo "$out"

  if echo "$out" | grep -q '^\[FAIL\]'; then
    sum="$(awk -v s="$sum" 'BEGIN{printf "%.2f", s+24.0}')"
    cnt=$((cnt+1))
  else
    # OK: parse "[Time] X hours"
    t="$(echo "$out" | awk '/^\[Time\]/{print $2; exit}')"
    if [ -z "$t" ]; then
      t="24.0"
    fi
    sum="$(awk -v s="$sum" -v t="$t" 'BEGIN{printf "%.2f", s+t}')"
    cnt=$((cnt+1))
  fi
done

avg="$(awk -v s="$sum" -v c="$cnt" 'BEGIN{printf "%.2f", s/c}')"

echo "--------------------------------"
echo "[AVG] over $cnt runs: $avg hours"