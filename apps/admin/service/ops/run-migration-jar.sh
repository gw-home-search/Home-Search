#!/usr/bin/env bash
set -Eeuo pipefail
jar_path="${ADMIN_MIGRATION_JAR:-}"
if [[ -z "${jar_path}" || ! -f "${jar_path}" ]]; then echo "ERROR: ADMIN_MIGRATION_JAR must identify an existing jar" >&2; exit 2; fi
operation=""
for argument in "$@"; do [[ "${argument}" == --operation=* ]] && operation="${argument#*=}"; done
case "${operation}" in info|validate|migrate) ;; *) echo "ERROR: supported --operation is required" >&2; exit 2 ;; esac
exec java -jar "${jar_path}" "$@"
