#!/usr/bin/env bash
set -Eeuo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
migration_jar="${ADMIN_MIGRATION_JAR:-}"
ops_jar="${ADMIN_OPS_JAR:-}"
if [[ -z "${migration_jar}" || ! -f "${migration_jar}" || -z "${ops_jar}" || ! -f "${ops_jar}" ]]; then
  echo "ERROR: ADMIN_MIGRATION_JAR and ADMIN_OPS_JAR must identify existing jars" >&2; exit 2
fi

set +e
ADMIN_MIGRATION_JAR="${migration_jar}" "${root}/ops/run-migration-jar.sh" --operation=unknown >/dev/null 2>&1
migration_exit="$?"
ADMIN_OPS_JAR="${ops_jar}" "${root}/ops/run-admin-ops-jar.sh" --operation=create-account --password=forbidden >/dev/null 2>&1
ops_exit="$?"
set -e
if [[ "${migration_exit}" != "2" || "${ops_exit}" != "2" ]]; then
  echo "ERROR: packaged wrappers did not preserve preflight exit code 2" >&2; exit 1
fi
echo "packaged-process smoke passed: migration and ops wrappers"
