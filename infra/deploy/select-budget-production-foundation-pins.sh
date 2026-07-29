#!/usr/bin/env bash
set -Eeuo pipefail

terraform_dir="${1:?budget-production Terraform directory is required}"
aws_region="${2:?AWS region is required}"
readonly name="home-search-budget-production"

fail() {
  echo "상태: Fail - $*" >&2
  exit 1
}

instances_json="$(
  aws ec2 describe-instances --region "${aws_region}" \
    --filters \
    "Name=tag:Name,Values=${name}-host" \
    "Name=tag:Environment,Values=budget-production" \
    "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --output json
)"
volumes_json="$(
  aws ec2 describe-volumes --region "${aws_region}" \
    --filters \
    "Name=tag:Name,Values=${name}-data" \
    "Name=tag:Environment,Values=budget-production" \
    "Name=status,Values=creating,available,in-use" \
    --output json
)"
subnets_json="$(
  aws ec2 describe-subnets --region "${aws_region}" \
    --filters \
    "Name=tag:Environment,Values=budget-production" \
    "Name=tag:Name,Values=${name}-public-*" \
    --output json
)"

hosts="$(
  jq -c '[.Reservations[].Instances[] | {
    id: .InstanceId,
    ami: .ImageId,
    az: .Placement.AvailabilityZone
  }]' <<<"${instances_json}"
)"
volumes="$(
  jq -c '[.Volumes[] | {
    id: .VolumeId,
    az: .AvailabilityZone
  }]' <<<"${volumes_json}"
)"
subnets="$(
  jq -c '[.Subnets[] | {
    id: .SubnetId,
    az: .AvailabilityZone
  }]' <<<"${subnets_json}"
)"

host_count="$(jq 'length' <<<"${hosts}")"
volume_count="$(jq 'length' <<<"${volumes}")"
subnet_count="$(jq 'length' <<<"${subnets}")"
(( host_count <= 1 )) || fail "tag가 일치하는 host가 ${host_count}개라 pin을 선택할 수 없습니다."
(( volume_count <= 1 )) || fail "tag가 일치하는 data EBS가 ${volume_count}개라 pin을 선택할 수 없습니다."
(( subnet_count <= 1 )) || fail "tag가 일치하는 subnet이 ${subnet_count}개라 pin을 선택할 수 없습니다."

partial_azs="$(
  jq -cn --argjson volumes "${volumes}" --argjson subnets "${subnets}" \
    '[($volumes[]?.az), ($subnets[]?.az)] | unique'
)"
partial_az_count="$(jq 'length' <<<"${partial_azs}")"
(( partial_az_count <= 1 )) || fail "부분 생성된 data EBS와 subnet의 AZ가 일치하지 않습니다."
partial_az="$(jq -r 'first // empty' <<<"${partial_azs}")"

emit_pins() {
  local ami="$1"
  local az="$2"
  local source="$3"
  [[ "${ami}" =~ ^ami-[0-9a-f]{17}$ ]] || fail "선택한 AMI 형식이 유효하지 않습니다."
  [[ "${az}" =~ ^ap-northeast-2[a-d]$ ]] || fail "선택한 AZ 형식이 유효하지 않습니다."
  jq -cn --arg ami_id "${ami}" --arg availability_zone "${az}" --arg pin_source "${source}" \
    '{ami_id:$ami_id,availability_zone:$availability_zone,pin_source:$pin_source}'
}

if (( host_count == 1 )); then
  host_ami="$(jq -r '.[0].ami' <<<"${hosts}")"
  host_az="$(jq -r '.[0].az' <<<"${hosts}")"
  [[ -z "${partial_az}" || "${partial_az}" == "${host_az}" ]] ||
    fail "host와 부분 생성 resource의 AZ가 일치하지 않습니다."
  emit_pins "${host_ami}" "${host_az}" host
  exit 0
fi

set +e
state_ami="$(terraform -chdir="${terraform_dir}" output -raw ami_id 2>/dev/null)"
state_ami_status=$?
state_az="$(terraform -chdir="${terraform_dir}" output -raw availability_zone 2>/dev/null)"
state_az_status=$?
set -e

if (( state_ami_status == 0 || state_az_status == 0 )); then
  (( state_ami_status == 0 && state_az_status == 0 )) ||
    fail "state의 AMI/AZ output 중 하나만 존재합니다."
  [[ -z "${partial_az}" || "${partial_az}" == "${state_az}" ]] ||
    fail "state와 부분 생성 resource의 AZ가 일치하지 않습니다."
  emit_pins "${state_ami}" "${state_az}" state
  exit 0
fi

recommended_ami="$(
  aws ssm get-parameter --region "${aws_region}" \
    --name /aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/image_id \
    --query Parameter.Value --output text
)"

if [[ -n "${partial_az}" ]]; then
  emit_pins "${recommended_ami}" "${partial_az}" partial-resources
  exit 0
fi

stable_az="$(
  aws ec2 describe-instance-type-offerings --region "${aws_region}" \
    --location-type availability-zone \
    --filters Name=instance-type,Values=t3a.large \
    --query 'InstanceTypeOfferings[].Location' --output json |
  jq -r '.[]' |
  while read -r zone; do
    zone_id="$(
      aws ec2 describe-availability-zones --region "${aws_region}" \
        --zone-names "${zone}" --query 'AvailabilityZones[0].ZoneId' --output text
    )"
    printf '%s\t%s\n' "${zone_id}" "${zone}"
  done |
  LC_ALL=C sort |
  head -n1 |
  cut -f2
)"
[[ -n "${stable_az}" ]] || fail "t3a.large stable AZ를 선택하지 못했습니다."
emit_pins "${recommended_ami}" "${stable_az}" recommended
