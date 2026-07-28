#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

database_identifiers="${1:?database identifier JSON is required}"
alarm_prefix="${2:?alarm prefix is required}"
snapshot_suffix="${3:?snapshot suffix is required}"
output="${4:?evidence output is required}"

jq -e '
  (keys | sort) == ["admin","ai","coordinate","property","user"]
  and ([.[] | type == "string" and test("^[a-z][a-z0-9-]{0,62}$")] | all)
' <<<"${database_identifiers}" >/dev/null || {
  echo '상태: Fail - 정확한 5개 production database identifier가 필요합니다.' >&2
  exit 1
}
[[ "${alarm_prefix}" =~ ^[A-Za-z0-9_-]{1,128}$ ]] || {
  echo '상태: Fail - alarm prefix 형식이 올바르지 않습니다.' >&2
  exit 1
}
[[ "${snapshot_suffix}" =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] || {
  echo '상태: Fail - snapshot suffix 형식이 올바르지 않습니다.' >&2
  exit 1
}
[[ ! -L "${output}" && -d "$(dirname "${output}")" && ! -L "$(dirname "${output}")" ]] || {
  echo '상태: Fail - evidence output 경로가 안전하지 않습니다.' >&2
  exit 1
}

snapshots='[]'
while IFS=$'\t' read -r logical database_identifier; do
  snapshot_identifier="${alarm_prefix,,}-${logical}-${snapshot_suffix}"
  snapshot_identifier="${snapshot_identifier//_/-}"
  aws rds create-db-snapshot --db-instance-identifier "${database_identifier}" \
    --db-snapshot-identifier "${snapshot_identifier}" >/dev/null
  aws rds wait db-snapshot-completed --db-snapshot-identifier "${snapshot_identifier}"
  description="$(aws rds describe-db-snapshots --db-snapshot-identifier "${snapshot_identifier}" --output json)"
  snapshot="$(jq -ec --arg logical "${logical}" --arg database "${database_identifier}" '
    .DBSnapshots | select(length == 1) | .[0]
    | select(.Status == "available" and .Encrypted == true)
    | {logical:$logical,database_identifier:$database,snapshot_arn:.DBSnapshotArn,status:.Status,encrypted:.Encrypted}
  ' <<<"${description}")" || {
    echo "상태: Fail - ${logical} pre-deploy snapshot이 encrypted available 상태가 아닙니다." >&2
    exit 1
  }
  snapshots="$(jq --argjson snapshot "${snapshot}" '. + [$snapshot]' <<<"${snapshots}")"
done < <(jq -r 'to_entries | sort_by(.key)[] | [.key,.value] | @tsv' <<<"${database_identifiers}")

alarms="$(aws cloudwatch describe-alarms --alarm-name-prefix "${alarm_prefix}" --output json)"
alarm_states="$(jq -ec '[
  (.MetricAlarms[]? | {name:.AlarmName,state:.StateValue,updated_at:.StateUpdatedTimestamp,type:"metric"}),
  (.CompositeAlarms[]? | {name:.AlarmName,state:.StateValue,updated_at:.StateUpdatedTimestamp,type:"composite"})
] | sort_by(.name)' <<<"${alarms}")"

temporary="$(mktemp "$(dirname "${output}")/.production-state.XXXXXX")"
cleanup() { unlink "${temporary}" 2>/dev/null || true; }
trap cleanup EXIT
jq -n --arg captured_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --argjson snapshots "${snapshots}" --argjson alarms "${alarm_states}" \
  '{status:"pass",captured_at:$captured_at,database_snapshots:$snapshots,alarm_states:$alarms}' \
  >"${temporary}"
chmod 0600 "${temporary}"
mv "${temporary}" "${output}"
trap - EXIT
echo '상태: Pass - 5개 RDS pre-deploy snapshot과 alarm state를 캡처했습니다.'
