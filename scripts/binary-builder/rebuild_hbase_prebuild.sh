#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="/home/shuai/xlab/rupfuzz"
PREBUILD_DIR="${ROOT_DIR}/prebuild/hbase"
BUILDER="${ROOT_DIR}/upfuzz-shuai/scripts/binary-builder/build_prebuild_binaries.sh"

VERSIONS=(
  "hbase-2.5.13"
  "hbase-2.6.4"
  "hbase-4.0.0-alpha-1-SNAPSHOT"
)

if [[ "$#" -gt 0 ]]; then
  VERSIONS=("$@")
fi

for version in "${VERSIONS[@]}"; do
  echo "[cleanup] ${version}"
  rm -rf "${PREBUILD_DIR}/${version}" "${PREBUILD_DIR}/${version}.tar.gz"
done

HBASE_VERSIONS_STR="${VERSIONS[*]}"
echo "[build] TARGETS=hbase HBASE_VERSIONS='${HBASE_VERSIONS_STR}' ${BUILDER}"
HBASE_VERSIONS="${HBASE_VERSIONS_STR}" TARGETS=hbase "${BUILDER}"
