#!/usr/bin/env bash
set -Eeuo pipefail

MIGRATION_JAR="${SOURCE_DATA_MIGRATION_JAR:-}"
if [[ -z "${MIGRATION_JAR}" || ! -f "${MIGRATION_JAR}" ]]; then
  echo "ERROR: SOURCE_DATA_MIGRATION_JAR must identify an existing packaged migration jar" >&2
  exit 2
fi

operation=""
for argument in "$@"; do
  case "${argument}" in
    --operation=*) operation="${argument#*=}" ;;
  esac
done
case "${operation}" in
  info|validate|migrate|preflight-baseline|baseline-existing) ;;
  *) echo "ERROR: a supported --operation is required" >&2; exit 2 ;;
esac

exec java -jar "${MIGRATION_JAR}" "$@"
