#!/usr/bin/env bash
set -Eeuo pipefail

BATCH_JAR="${PROPERTY_DATA_BATCH_JAR:-}"

if [[ -z "${BATCH_JAR}" ]]; then
  echo "ERROR: PROPERTY_DATA_BATCH_JAR must identify the packaged batch jar" >&2
  exit 2
fi
if [[ ! -f "${BATCH_JAR}" ]]; then
  echo "ERROR: packaged batch jar does not exist: ${BATCH_JAR}" >&2
  exit 2
fi

exec java -jar "${BATCH_JAR}" "$@"
