#!/usr/bin/env bash
set -Eeuo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/capture-production-state.sh"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
mkdir -p "${tmp_dir}/bin"
cat >"${tmp_dir}/bin/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%q ' "$@" >>"${FAKE_AWS_LOG}"
printf '\n' >>"${FAKE_AWS_LOG}"
case "$*" in
  *'rds create-db-snapshot'*) printf '%s\n' '{}' ;;
  *'rds wait db-snapshot-completed'*) ;;
  *'rds describe-db-snapshots'*)
    snapshot=''
    previous=''
    for argument in "$@"; do
      if [[ "${previous}" == '--db-snapshot-identifier' ]]; then snapshot="${argument}"; fi
      previous="${argument}"
    done
    jq -n --arg snapshot "${snapshot}" '{DBSnapshots:[{DBSnapshotArn:("arn:aws:rds:snapshot:"+$snapshot),Status:"available",Encrypted:true}]}'
    ;;
  *'cloudwatch describe-alarms'*)
    printf '%s\n' '{"MetricAlarms":[{"AlarmName":"home-search-production-map-p95","StateValue":"OK","StateUpdatedTimestamp":"2026-07-28T00:00:00Z"}],"CompositeAlarms":[]}'
    ;;
  *) exit 2 ;;
esac
FAKE_AWS
chmod +x "${tmp_dir}/bin/aws"
export FAKE_AWS_LOG="${tmp_dir}/aws.log"
: >"${FAKE_AWS_LOG}"
PATH="${tmp_dir}/bin:${PATH}" "${script}" \
  '{"property":"property-db","admin":"admin-db","user":"user-db","ai":"ai-db","coordinate":"coordinate-db"}' \
  home-search-production pre-123 "${tmp_dir}/evidence.json"
jq -e '
  .status == "pass"
  and (.database_snapshots | length) == 5
  and ([.database_snapshots[].encrypted] | all)
  and .alarm_states[0].name == "home-search-production-map-p95"
' "${tmp_dir}/evidence.json" >/dev/null
[[ "$(grep -c 'rds create-db-snapshot' "${FAKE_AWS_LOG}")" == '5' ]]
grep -Fq -- '--db-snapshot-identifier home-search-production-property-pre-123' "${FAKE_AWS_LOG}"

set +e
PATH="${tmp_dir}/bin:${PATH}" "${script}" \
  '{"property":"property-db","admin":"admin-db","user":"user-db","ai":"ai-db","coordinate":"coordinate-db","extra":"bad"}' \
  home-search-production pre-123 "${tmp_dir}/invalid.json" >/dev/null 2>&1
invalid_code=$?
set -e
[[ "${invalid_code}" == '1' ]]
echo '상태: Pass - 5개 RDS snapshot과 alarm state evidence 계약을 확인했습니다.'
