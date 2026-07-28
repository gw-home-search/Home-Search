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
  *'-outputType=json validate'*)
    printf '%s\n' "${FAKE_VALIDATE:-}"
    exit "${FAKE_VALIDATE_EXIT:-0}"
    ;;
  *) exit 1 ;;
esac
FAKE
chmod +x "${TEMP}/bin/docker"
: > "${TEMP}/docker.log"
pending='{"migrations":[{"version":"1","type":"SQL","state":"Pending"},{"version":"2","type":"SQL","state":"Pending"},{"version":"4","type":"SQL","state":"Pending"},{"version":"5","type":"SQL","state":"Pending"},{"version":"6","type":"SQL","state":"Pending"},{"version":"7","type":"SQL","state":"Pending"},{"version":"8","type":"SQL","state":"Pending"},{"version":"9","type":"SQL","state":"Pending"},{"version":"10","type":"SQL","state":"Pending"},{"version":"11","type":"SQL","state":"Pending"},{"version":"12","type":"SQL","state":"Pending"},{"version":"13","type":"SQL","state":"Pending"},{"version":"14","type":"SQL","state":"Pending"},{"version":"15","type":"SQL","state":"Pending"},{"version":"16","type":"SQL","state":"Pending"},{"version":"17","type":"SQL","state":"Pending"},{"version":"18","type":"SQL","state":"Pending"},{"version":"19","type":"SQL","state":"Pending"},{"version":"20","type":"SQL","state":"Pending"},{"version":"21","type":"SQL","state":"Pending"},{"version":"22","type":"SQL","state":"Pending"},{"version":"23","type":"SQL","state":"Pending"},{"version":"24","type":"SQL","state":"Pending"},{"version":"25","type":"SQL","state":"Pending"},{"version":"26","type":"SQL","state":"Pending"},{"version":"27","type":"SQL","state":"Pending"},{"version":"28","type":"SQL","state":"Pending"},{"version":"29","type":"SQL","state":"Pending"},{"version":"30","type":"SQL","state":"Pending"},{"version":"31","type":"SQL","state":"Pending"},{"version":"32","type":"SQL","state":"Pending"},{"version":"33","type":"SQL","state":"Pending"},{"version":"34","type":"SQL","state":"Pending"},{"version":"35","type":"SQL","state":"Pending"},{"version":"36","type":"SQL","state":"Pending"},{"version":"37","type":"SQL","state":"Pending"},{"version":"38","type":"SQL","state":"Pending"},{"version":"39","type":"SQL","state":"Pending"}]}'
info_with_v2(){
  local type="$1" state="$2" extra="${3:-}"
  printf '{"migrations":[{"version":"1","type":"SQL","state":"Success"},{"version":"2","type":"%s","state":"%s"},{"version":"4","type":"SQL","state":"Success"},{"version":"5","type":"SQL","state":"Success"},{"version":"6","type":"SQL","state":"Success"},{"version":"7","type":"SQL","state":"Success"},{"version":"8","type":"SQL","state":"Success"},{"version":"9","type":"SQL","state":"Success"},{"version":"10","type":"SQL","state":"Success"},{"version":"11","type":"SQL","state":"Success"},{"version":"12","type":"SQL","state":"Success"},{"version":"13","type":"SQL","state":"Success"},{"version":"14","type":"SQL","state":"Success"},{"version":"15","type":"SQL","state":"Success"},{"version":"16","type":"SQL","state":"Success"},{"version":"17","type":"SQL","state":"Success"},{"version":"18","type":"SQL","state":"Success"},{"version":"19","type":"SQL","state":"Success"},{"version":"20","type":"SQL","state":"Success"},{"version":"21","type":"SQL","state":"Success"},{"version":"22","type":"SQL","state":"Success"},{"version":"23","type":"SQL","state":"Success"},{"version":"24","type":"SQL","state":"Success"},{"version":"25","type":"SQL","state":"Success"},{"version":"26","type":"SQL","state":"Success"},{"version":"27","type":"SQL","state":"Success"},{"version":"28","type":"SQL","state":"Success"},{"version":"29","type":"SQL","state":"Success"},{"version":"30","type":"SQL","state":"Success"},{"version":"31","type":"SQL","state":"Success"},{"version":"32","type":"SQL","state":"Success"},{"version":"33","type":"SQL","state":"Success"},{"version":"34","type":"SQL","state":"Success"},{"version":"35","type":"SQL","state":"Success"},{"version":"36","type":"SQL","state":"Success"},{"version":"37","type":"SQL","state":"Success"},{"version":"38","type":"SQL","state":"Success"},{"version":"39","type":"SQL","state":"Success"}%s]}' "${type}" "${state}" "${extra}"
}
success="$(info_with_v2 SQL Success)"
jdbc="$(info_with_v2 JDBC Success)"
deleted="$(info_with_v2 SQL Deleted)"
out_of_order="$(info_with_v2 SQL 'Out of Order')"
missing="$(info_with_v2 SQL Missing)"
ignored="$(info_with_v2 SQL Ignored)"
failed="$(info_with_v2 SQL Failed)"
duplicate="$(info_with_v2 SQL Success ',{"version":"2","type":"SQL","state":"Success"}')"
rows=$'<null>|SCHEMA|t\n1|SQL|t\n2|SQL|t\n4|SQL|t\n5|SQL|t\n6|SQL|t\n7|SQL|t\n8|SQL|t\n9|SQL|t\n10|SQL|t\n11|SQL|t\n12|SQL|t\n13|SQL|t\n14|SQL|t\n15|SQL|t\n16|SQL|t\n17|SQL|t\n18|SQL|t\n19|SQL|t\n20|SQL|t\n21|SQL|t\n22|SQL|t\n23|SQL|t\n24|SQL|t\n25|SQL|t\n26|SQL|t\n27|SQL|t\n28|SQL|t\n29|SQL|t\n30|SQL|t\n31|SQL|t\n32|SQL|t\n33|SQL|t\n34|SQL|t\n35|SQL|t\n36|SQL|t\n37|SQL|t\n38|SQL|t\n39|SQL|t\n'
failed_rows="${rows/2|SQL|t/2|SQL|f}"
invoke(){ env PATH="${TEMP}/bin:${PATH}" FAKE_LOG="${TEMP}/docker.log" FAKE_VALIDATE='{"validationSuccessful":true,"invalidMigrations":[]}' PROPERTY_MIGRATOR_JDBC_URL=jdbc:postgresql://db:5432/home_search PROPERTY_MIGRATOR_DB_USERNAME=migrator PROPERTY_MIGRATOR_DB_PASSWORD=sentinel "$@"; }
reject(){ expected="$1"; shift; set +e; "$@" >"${TEMP}/out" 2>"${TEMP}/err"; code=$?; set -e; [[ "${code}" -eq "${expected}" ]] && ! grep -Fq sentinel "${TEMP}/out" "${TEMP}/err"; }
bash -n "${SCRIPT}"
reject 2 env -u PROPERTY_MIGRATOR_DB_PASSWORD "${SCRIPT}" before 39
reject 2 invoke "${SCRIPT}" before
reject 2 invoke "${SCRIPT}" before x
: > "${TEMP}/docker.log"
reject 2 invoke "${SCRIPT}" legacy-before 39
[[ ! -s "${TEMP}/docker.log" ]]
: > "${TEMP}/docker.log"
reject 2 invoke env PROPERTY_MIGRATOR_JDBC_URL='jdbc:postgresql://db:5432/wrong_database' "${SCRIPT}" before 39
[[ ! -s "${TEMP}/docker.log" ]]
reject 2 invoke env PROPERTY_MIGRATOR_JDBC_URL='jdbc:postgresql://migrator:authority-sentinel@db:5432/home_search' "${SCRIPT}" before 39
[[ ! -s "${TEMP}/docker.log" ]]
reject 2 invoke env PROPERTY_MIGRATOR_JDBC_URL='jdbc:postgresql://db:5432/home_search?Password=query-sentinel' "${SCRIPT}" before 39
[[ ! -s "${TEMP}/docker.log" ]]
! grep -Fq authority-sentinel "${TEMP}/out" "${TEMP}/err" "${TEMP}/docker.log"
! grep -Fq query-sentinel "${TEMP}/out" "${TEMP}/err" "${TEMP}/docker.log"
reject 2 invoke env FAKE_DATABASE=wrong FAKE_INFO="${pending}" "${SCRIPT}" before 39
reject 2 invoke env FAKE_INFO="${pending}" "${SCRIPT}" before 8
reject 2 invoke env FAKE_RELATIONS=1 FAKE_INFO="${pending}" "${SCRIPT}" before 39
[[ "$(invoke env FAKE_INFO="${pending}" "${SCRIPT}" before 39)" == 'service=property-data phase=before target=39 state=EMPTY' ]]
[[ "$(invoke env FAKE_HISTORY=true FAKE_RELATIONS=32 FAKE_INFO="${success}" FAKE_ROWS="${rows}" "${SCRIPT}" after 39)" == 'service=property-data phase=after target=39 state=READY' ]]
reject 2 invoke env FAKE_HISTORY=true FAKE_INFO="${jdbc}" FAKE_ROWS="${rows}" "${SCRIPT}" after 39
reject 2 invoke env FAKE_HISTORY=true FAKE_INFO="${deleted}" FAKE_ROWS="${rows}" "${SCRIPT}" after 39
reject 2 invoke env FAKE_HISTORY=true FAKE_INFO="${out_of_order}" FAKE_ROWS="${rows}" "${SCRIPT}" after 39
reject 2 invoke env FAKE_HISTORY=true FAKE_INFO="${missing}" FAKE_ROWS="${rows}" "${SCRIPT}" after 39
reject 2 invoke env FAKE_HISTORY=true FAKE_INFO="${ignored}" FAKE_ROWS="${rows}" "${SCRIPT}" after 39
reject 2 invoke env FAKE_HISTORY=true FAKE_INFO="${failed}" FAKE_ROWS="${rows}" "${SCRIPT}" after 39
reject 2 invoke env FAKE_HISTORY=true FAKE_INFO="${duplicate}" FAKE_ROWS="${rows}" "${SCRIPT}" after 39
reject 2 invoke env FAKE_HISTORY=true FAKE_INFO="${success}" FAKE_ROWS="${failed_rows}" "${SCRIPT}" after 39
reject 2 invoke env FAKE_HISTORY=true FAKE_INFO="${success}" FAKE_ROWS="${rows}"$'<null>|BASELINE|t\n' "${SCRIPT}" after 39
reject 1 invoke env FAKE_HISTORY=true FAKE_INFO="${success}" FAKE_ROWS="${rows}" FAKE_VALIDATE='{"error":{"message":"validation failed"}}' FAKE_VALIDATE_EXIT=1 "${SCRIPT}" after 39
[[ "$(invoke env FAKE_HISTORY=true FAKE_RELATIONS=32 FAKE_INFO="${success}" FAKE_ROWS="${rows}" FAKE_VALIDATE= "${SCRIPT}" after 39)" == 'service=property-data phase=after target=39 state=READY' ]]
! grep -Fq sentinel "${TEMP}/docker.log"
printf 'property deployment preflight contract passed\n'
