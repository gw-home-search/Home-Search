#!/usr/bin/env bash
set -euo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SCRIPT="${ROOT}/ops/building-register-profile-ratio-backfill.sh"
readonly TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
mkdir -p "${TEMP}/bin" "${TEMP}/work"

cat >"${TEMP}/bin/psql" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
body="$(cat)"
printf '%s\n' "$body" >>"${FAKE_PSQL_LOG}"
case "$body" in
  *building_register_profile_archive_manifest*) printf 'yes\n' ;;
  *building_ratio_profile_backfill_import*)
    printf 'COMPLETED|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa|34462\n'
    ;;
  *) exit 1 ;;
esac
FAKE
chmod +x "${TEMP}/bin/psql"

readonly COLLECTION_ID=11111111-1111-4111-8111-111111111111
readonly ANALYSIS_ID=22222222-2222-4222-8222-222222222222
readonly ARCHIVE_ID=33333333-3333-4333-8333-333333333333
readonly IMPORT_ID=44444444-4444-4444-8444-444444444444

invoke() {
  env PATH="${TEMP}/bin:${PATH}" \
    FAKE_PSQL_LOG="${TEMP}/psql.log" \
    PROFILE_COLLECTION_ID="$COLLECTION_ID" \
    PROFILE_ANALYSIS_RUN_ID="$ANALYSIS_ID" \
    PROFILE_ARCHIVE_ID="$ARCHIVE_ID" \
    PROFILE_RATIO_IMPORT_ID="$IMPORT_ID" \
    PROFILE_RATIO_WORK_DIRECTORY="${TEMP}/work" \
    PROPERTY_DB_HOST=127.0.0.1 \
    PROPERTY_DB_PASSWORD=secret-sentinel \
    "$@"
}

bash -n "$SCRIPT"
output="$(invoke "$SCRIPT" import 2>"${TEMP}/err")"
[[ "$output" == '이미 완료된 import evidence가 있어 재적재하지 않습니다' ]]
[[ "$(wc -l <"${TEMP}/psql.log")" -gt 1 ]]
! grep -Fq secret-sentinel "${TEMP}/err" "${TEMP}/psql.log"
! printf '%s\n' "$output" | grep -Fq secret-sentinel

set +e
invoke env -u PROPERTY_DB_PASSWORD "$SCRIPT" import >"${TEMP}/out" 2>"${TEMP}/err"
code=$?
set -e
[[ "$code" -eq 1 ]]
grep -Fq 'PROPERTY_DB_PASSWORD가 필요합니다' "${TEMP}/err"
! grep -Fq secret-sentinel "${TEMP}/out" "${TEMP}/err"

printf 'building profile ratio backfill contract passed\n'
