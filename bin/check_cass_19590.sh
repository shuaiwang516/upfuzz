# !/bin/bash
if [ $# -eq 0 ]; then
  set -- failure
fi

bin/check_keyword.sh "$@" "replaying a mutation"