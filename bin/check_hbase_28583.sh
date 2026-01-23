# !/bin/bash
if [ $# -eq 0 ]; then
  set -- failure
fi

bin/check_keyword.sh "$@" "old_table_schema"