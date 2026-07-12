#!/usr/bin/env bash
set -Eeuo pipefail
jar_path="${ADMIN_OPS_JAR:-}"
if [[ -z "${jar_path}" || ! -f "${jar_path}" ]]; then echo "ERROR: ADMIN_OPS_JAR must identify an existing jar" >&2; exit 2; fi
for argument in "$@"; do
  if [[ "${argument}" == --password=* ]]; then echo "ERROR: password command arguments are forbidden" >&2; exit 2; fi
done
exec java -jar "${jar_path}" "$@"
