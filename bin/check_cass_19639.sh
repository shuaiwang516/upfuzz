# !/bin/bash
if [ $# -eq 0 ]; then
  set -- failure
fi

bin/check_keyword.sh "$@" "java.lang.RuntimeException: java.lang.NullPointerException"