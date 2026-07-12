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

operation=""
for argument in "$@"; do
  case "${argument}" in
    --operation=*) operation="${argument#--operation=}" ;;
  esac
done

case "${operation}" in
  migrate|repair-missing-v3|backfill-registry-trade-date)
    evidence_file="${MIGRATION_EVIDENCE_FILE:-}"
    if [[ -z "${evidence_file}" ]]; then
      echo "ERROR: MIGRATION_EVIDENCE_FILE is required for mutating migration operations" >&2
      exit 2
    fi
    evidence_dir="$(dirname "${evidence_file}")"
    if [[ ! -d "${evidence_dir}" ]]; then
      echo "ERROR: migration evidence directory does not exist: ${evidence_dir}" >&2
      exit 2
    fi
    repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
    git_sha="$(git -C "${repo_root}" rev-parse HEAD)"
    jar_sha256="$(shasum -a 256 "${MIGRATION_JAR}" | awk '{print $1}')"
    umask 077
    {
      echo "recorded_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      echo "operation=${operation}"
      echo "git_sha=${git_sha}"
      echo "migration_jar_sha256=${jar_sha256}"
    } >> "${evidence_file}"
    ;;
esac

exec java -jar "${MIGRATION_JAR}" "$@"
