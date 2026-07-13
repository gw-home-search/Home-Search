#!/usr/bin/env bash
set -euo pipefail
readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SCRIPT="${ROOT}/ops/property-deployment-preflight.sh"
readonly TEMP="$(mktemp -d)"
trap 'rm -rf "${TEMP}"' EXIT
mkdir -p "${TEMP}/bin"
cat > "${TEMP}/bin/docker" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${FAKE_LOG}"
case "$*" in
  *current_database*) printf '%s\n' "${FAKE_DATABASE:-home_search}" ;;
  *to_regclass*) printf '%s\n' "${FAKE_HISTORY:-false}" ;;
  *service_owned_relations*) printf '%s\n' "${FAKE_RELATIONS:-0}" ;;
  *preflight_history_rows*) printf '%b' "${FAKE_ROWS:-}" ;;
  *'-outputType=json info'*) printf '%s\n' "${FAKE_INFO:-}" ;;
  *'-outputType=json validate'*) printf '%s\n' "${FAKE_VALIDATE:-}" ;;
  *) exit 1 ;;
esac
FAKE
chmod +x "${TEMP}/bin/docker"
: > "${TEMP}/docker.log"
pending='{"migrations":[{"version":"1","type":"SQL","state":"Pending"},{"version":"2","type":"SQL","state":"Pending"},{"version":"4","type":"SQL","state":"Pending"},{"version":"5","type":"SQL","state":"Pending"},{"version":"6","type":"SQL","state":"Pending"},{"version":"7","type":"SQL","state":"Pending"},{"version":"8","type":"SQL","state":"Pending"}]}'
success="${pending//Pending/Success}"
rows=$'<null>|SCHEMA|t\n1|SQL|t\n2|SQL|t\n4|SQL|t\n5|SQL|t\n6|SQL|t\n7|SQL|t\n8|SQL|t\n'
invoke(){ env PATH="${TEMP}/bin:${PATH}" FAKE_LOG="${TEMP}/docker.log" FAKE_VALIDATE='{"validationSuccessful":true,"invalidMigrations":[]}' PROPERTY_MIGRATOR_JDBC_URL=jdbc:postgresql://db:5432/home_search PROPERTY_MIGRATOR_DB_USERNAME=migrator PROPERTY_MIGRATOR_DB_PASSWORD=sentinel "$@"; }
reject(){ expected="$1"; shift; set +e; "$@" >"${TEMP}/out" 2>"${TEMP}/err"; code=$?; set -e; [[ "${code}" -eq "${expected}" ]] && ! grep -Fq sentinel "${TEMP}/out" "${TEMP}/err"; }
bash -n "${SCRIPT}"
reject 2 env -u PROPERTY_MIGRATOR_DB_PASSWORD "${SCRIPT}" before 8
reject 2 invoke "${SCRIPT}" before x
reject 2 invoke env FAKE_DATABASE=wrong FAKE_INFO="${pending}" "${SCRIPT}" before 8
reject 2 invoke env FAKE_RELATIONS=1 FAKE_INFO="${pending}" "${SCRIPT}" before 8
[[ "$(invoke env FAKE_INFO="${pending}" "${SCRIPT}" before 8)" == 'service=property-data phase=before target=8 state=EMPTY' ]]
[[ "$(invoke env FAKE_HISTORY=true FAKE_RELATIONS=20 FAKE_INFO="${success}" FAKE_ROWS="${rows}" "${SCRIPT}" after 8)" == 'service=property-data phase=after target=8 state=READY' ]]
reject 2 invoke env FAKE_HISTORY=true FAKE_INFO='{"migrations":[{"version":"2","type":"JDBC","state":"Success"}]}' FAKE_ROWS=$'2|JDBC|t\n' "${SCRIPT}" after 8
reject 2 invoke env FAKE_HISTORY=true FAKE_INFO="${success}" FAKE_ROWS="${rows}"$'<null>|BASELINE|t\n' "${SCRIPT}" after 8
reject 2 invoke env FAKE_HISTORY=true FAKE_INFO="${success}" FAKE_ROWS="${rows}" FAKE_VALIDATE='{"validationSuccessful":false,"invalidMigrations":[{"version":"2"}]}' "${SCRIPT}" after 8
! grep -Fq sentinel "${TEMP}/docker.log"
printf 'property deployment preflight contract passed\n'
