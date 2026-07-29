#!/usr/bin/env bash
set -Eeuo pipefail

plan_json="${1:?Terraform plan JSON is required}"
requested_phase="${2:?requested phase is required}"
current_phase="${3:-registry}"
[[ -f "${plan_json}" ]] || exit 2

phase_index() {
  case "$1" in
    registry) echo 0 ;;
    foundation) echo 1 ;;
    data) echo 2 ;;
    private) echo 3 ;;
    public) echo 4 ;;
    *) return 2 ;;
  esac
}

requested_index="$(phase_index "${requested_phase}")" || {
  echo "상태: Fail - 알 수 없는 requested phase입니다: ${requested_phase}" >&2
  exit 2
}
current_index="$(phase_index "${current_phase}")" || {
  echo "상태: Fail - 알 수 없는 current phase입니다: ${current_phase}" >&2
  exit 2
}
if (( requested_index < current_index )); then
  echo "상태: Fail - phase backslide를 거부합니다: ${current_phase} -> ${requested_phase}" >&2
  exit 1
fi

violations="$(jq -c '
  [
    "aws_db_instance", "aws_db_cluster", "aws_rds_cluster", "aws_msk_cluster", "aws_msk_serverless_cluster",
    "aws_nat_gateway", "aws_lb", "aws_lb_listener", "aws_lb_target_group", "aws_vpn_gateway",
    "aws_customer_gateway", "aws_elasticache_cluster", "aws_elasticache_replication_group",
    "aws_prometheus_workspace", "grafana_workspace", "aws_ebs_fast_snapshot_restore"
  ] as $forbidden_types |
  [.resource_changes[]
   | select(.mode == "managed")
   | select(.change.actions != ["no-op"] and .change.actions != ["read"])
   | . as $change
   | select(
       (.change.actions | index("delete")) != null
       or ($forbidden_types | index($change.type)) != null
       or ((.change.after | tostring) | test("home-search-(staging|production)([^-]|$)"))
     )
   | {address,type,actions:.change.actions}]
' "${plan_json}")"

if [[ "$(jq 'length' <<<"${violations}")" != '0' ]]; then
  echo '상태: Fail - budget-production plan이 zero-destroy 또는 금지 resource/state 경계를 벗어났습니다.' >&2
  jq . <<<"${violations}" >&2
  exit 1
fi

if [[ "${requested_phase}" == 'registry' ]]; then
  jq -e '
    all(.resource_changes[];
      .change.actions == ["no-op"] or .change.actions == ["read"]
      or .type == "aws_ecr_repository" or .type == "aws_ecr_lifecycle_policy")
  ' "${plan_json}" >/dev/null || {
    echo '상태: Fail - registry phase에 ECR 외 변경이 포함됐습니다.' >&2
    exit 1
  }
fi

echo "상태: Pass - ${current_phase} -> ${requested_phase} plan은 monotonic, zero-destroy, budget allowlist 경계를 충족합니다."
