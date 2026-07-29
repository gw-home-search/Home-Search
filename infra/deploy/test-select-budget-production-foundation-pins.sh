#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/select-budget-production-foundation-pins.sh"
temp_dir="$(mktemp -d)"
trap 'rm -rf "${temp_dir}"' EXIT

mkdir -p "${temp_dir}/bin"
cat >"${temp_dir}/bin/terraform" <<'FAKE_TERRAFORM'
#!/usr/bin/env bash
set -Eeuo pipefail
name="${*: -1}"
case "${TEST_SCENARIO:?}:${name}" in
  state:ami_id) printf '%s\n' ami-0123456789abcdef0 ;;
  state:availability_zone) printf '%s\n' ap-northeast-2b ;;
  *) exit 1 ;;
esac
FAKE_TERRAFORM
chmod +x "${temp_dir}/bin/terraform"

cat >"${temp_dir}/bin/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
set -Eeuo pipefail
args=" $* "
scenario="${TEST_SCENARIO:?}"
if [[ "${args}" == *' ec2 describe-instances '* ]]; then
  case "${scenario}" in
    host) printf '%s\n' '{"Reservations":[{"Instances":[{"InstanceId":"i-0123456789abcdef0","ImageId":"ami-0fedcba9876543210","State":{"Name":"running"},"Placement":{"AvailabilityZone":"ap-northeast-2c"}}]}]}' ;;
    *) printf '%s\n' '{"Reservations":[]}' ;;
  esac
elif [[ "${args}" == *' ec2 describe-volumes '* ]]; then
  case "${scenario}" in
    partial|mismatch) printf '%s\n' '{"Volumes":[{"VolumeId":"vol-0123456789abcdef0","AvailabilityZone":"ap-northeast-2a","State":"available"}]}' ;;
    state) printf '%s\n' '{"Volumes":[{"VolumeId":"vol-0123456789abcdef0","AvailabilityZone":"ap-northeast-2b","State":"available"}]}' ;;
    *) printf '%s\n' '{"Volumes":[]}' ;;
  esac
elif [[ "${args}" == *' ec2 describe-subnets '* ]]; then
  case "${scenario}" in
    partial) printf '%s\n' '{"Subnets":[{"SubnetId":"subnet-0123456789abcdef0","AvailabilityZone":"ap-northeast-2a"}]}' ;;
    mismatch|state) printf '%s\n' '{"Subnets":[{"SubnetId":"subnet-0123456789abcdef0","AvailabilityZone":"ap-northeast-2b"}]}' ;;
    *) printf '%s\n' '{"Subnets":[]}' ;;
  esac
elif [[ "${args}" == *' ssm get-parameter '* ]]; then
  printf '%s\n' ami-0aaaaaaaaaaaaaaaa
elif [[ "${args}" == *' ec2 describe-instance-type-offerings '* ]]; then
  printf '%s\n' '["ap-northeast-2a","ap-northeast-2b"]'
elif [[ "${args}" == *' ec2 describe-availability-zones '* ]]; then
  if [[ "${args}" == *'ap-northeast-2a'* ]]; then
    printf '%s\n' apne2-az2
  else
    printf '%s\n' apne2-az1
  fi
else
  echo "unexpected aws call:${args}" >&2
  exit 64
fi
FAKE_AWS
chmod +x "${temp_dir}/bin/aws"

run_selector() {
  TEST_SCENARIO="$1" PATH="${temp_dir}/bin:${PATH}" \
    "${script}" "${root}/infra/terraform/budget-production" ap-northeast-2
}

result="$(run_selector host)"
jq -e '.ami_id == "ami-0fedcba9876543210" and .availability_zone == "ap-northeast-2c" and .pin_source == "host"' <<<"${result}" >/dev/null

result="$(run_selector state)"
jq -e '.ami_id == "ami-0123456789abcdef0" and .availability_zone == "ap-northeast-2b" and .pin_source == "state"' <<<"${result}" >/dev/null

result="$(run_selector partial)"
jq -e '.ami_id == "ami-0aaaaaaaaaaaaaaaa" and .availability_zone == "ap-northeast-2a" and .pin_source == "partial-resources"' <<<"${result}" >/dev/null

if run_selector mismatch >"${temp_dir}/mismatch.out" 2>"${temp_dir}/mismatch.err"; then
  echo '상태: Fail - 부분 생성된 data EBS와 subnet의 AZ 불일치를 허용했습니다.' >&2
  exit 1
fi
grep -Fq 'AZ' "${temp_dir}/mismatch.err"
[[ ! -s "${temp_dir}/mismatch.out" ]]

result="$(run_selector empty)"
jq -e '.ami_id == "ami-0aaaaaaaaaaaaaaaa" and .availability_zone == "ap-northeast-2b" and .pin_source == "recommended"' <<<"${result}" >/dev/null

echo '상태: Pass - host/state/부분 resource/신규 foundation pin 우선순위와 AZ 불일치를 확인했습니다.'
