#!/usr/bin/env bash
set -Eeuo pipefail

MIGRATION_JAR="${PROPERTY_DATA_MIGRATION_JAR:-}"

if [[ -z "${MIGRATION_JAR}" ]]; then
  echo "ERROR: PROPERTY_DATA_MIGRATION_JAR must identify the packaged migration jar" >&2
  exit 2
fi
if [[ ! -f "${MIGRATION_JAR}" ]]; then
  echo "ERROR: packaged migration jar does not exist: ${MIGRATION_JAR}" >&2
  exit 2
fi

exec java -jar "${MIGRATION_JAR}" "$@"
