#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077

usage() {
  echo '사용법: run-recovery-rehearsal.sh RUN_ID AMI_ID SUBNET_ID SECURITY_GROUP_ID INSTANCE_PROFILE BACKUP_IMAGE BACKUP_BUCKET [SNAPSHOT_ID POSTGRES_IMAGE]' >&2
}
[[ "$#" == 7 || "$#" == 9 ]] || { usage; exit 2; }

run_id="$1"
ami_id="$2"
subnet_id="$3"
security_group_id="$4"
instance_profile="$5"
backup_image="$6"
backup_bucket="$7"
snapshot_id="${8:-}"
postgres_image="${9:-}"
mode='logical'
if [[ -n "${snapshot_id}" || -n "${postgres_image}" ]]; then
  [[ -n "${snapshot_id}" && -n "${postgres_image}" ]] || { usage; exit 2; }
  mode='ebs'
fi
region="${AWS_REGION:-ap-northeast-2}"

[[ "${region}" == 'ap-northeast-2' ]]
[[ "${run_id}" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$ ]]
[[ "${ami_id}" =~ ^ami-[0-9a-f]{17}$ ]]
[[ "${subnet_id}" =~ ^subnet-[0-9a-f]{8,17}$ ]]
[[ "${security_group_id}" =~ ^sg-[0-9a-f]{8,17}$ ]]
[[ "${instance_profile}" =~ ^[A-Za-z0-9+=,.@_-]{1,128}$ ]]
[[ "${backup_image}" =~ ^[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/backup@sha256:[0-9a-f]{64}$ ]]
[[ "${backup_bucket}" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]]
if [[ "${mode}" == 'ebs' ]]; then
  [[ "${snapshot_id}" =~ ^snap-[0-9a-f]{8,17}$ ]]
  [[ "${postgres_image}" =~ ^[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/budget-postgres@sha256:[0-9a-f]{64}$ ]]
fi

tmp_dir="$(mktemp -d)"
instance_id=''
volume_id=''
cleanup() {
  if [[ -n "${instance_id}" ]]; then
    purpose="$(aws ec2 describe-tags --region "${region}" \
      --filters "Name=resource-id,Values=${instance_id}" 'Name=key,Values=Purpose' \
      --query 'Tags[0].Value' --output text 2>/dev/null || true)"
    actual_run_id="$(aws ec2 describe-tags --region "${region}" \
      --filters "Name=resource-id,Values=${instance_id}" 'Name=key,Values=RunId' \
      --query 'Tags[0].Value' --output text 2>/dev/null || true)"
    if [[ "${purpose}" == 'budget-production-recovery' && "${actual_run_id}" == "${run_id}" ]]; then
      aws ec2 terminate-instances --region "${region}" --instance-ids "${instance_id}" >/dev/null
      aws ec2 wait instance-terminated --region "${region}" --instance-ids "${instance_id}"
    else
      echo '상태: Fail - recovery instance tag 재검증 실패로 자동 종료를 거부합니다.' >&2
      return 1
    fi
  fi
  if [[ -n "${volume_id}" ]]; then
    purpose="$(aws ec2 describe-tags --region "${region}" \
      --filters "Name=resource-id,Values=${volume_id}" 'Name=key,Values=Purpose' \
      --query 'Tags[0].Value' --output text 2>/dev/null || true)"
    actual_run_id="$(aws ec2 describe-tags --region "${region}" \
      --filters "Name=resource-id,Values=${volume_id}" 'Name=key,Values=RunId' \
      --query 'Tags[0].Value' --output text 2>/dev/null || true)"
    if [[ "${purpose}" == 'budget-production-recovery-clone' && "${actual_run_id}" == "${run_id}" ]]; then
      aws ec2 wait volume-available --region "${region}" --volume-ids "${volume_id}"
      aws ec2 delete-volume --region "${region}" --volume-id "${volume_id}"
    else
      echo '상태: Fail - recovery volume tag 재검증 실패로 자동 삭제를 거부합니다.' >&2
      return 1
    fi
  fi
  find "${tmp_dir}" -depth -delete 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

architecture="$(aws ec2 describe-images --region "${region}" --image-ids "${ami_id}" \
  --query 'Images[0].[State,Architecture]' --output text)"
[[ "${architecture}" == $'available\tx86_64' ]] || {
  echo '상태: Fail - recovery AMI는 available x86_64여야 합니다.' >&2
  exit 1
}

availability_zone="$(aws ec2 describe-subnets --region "${region}" --subnet-ids "${subnet_id}" \
  --query 'Subnets[0].AvailabilityZone' --output text)"
[[ "${availability_zone}" == ap-northeast-2[a-d] ]]

if [[ "${mode}" == 'ebs' ]]; then
  snapshot="$(aws ec2 describe-snapshots --region "${region}" --snapshot-ids "${snapshot_id}" \
    --query 'Snapshots[0].[State,Encrypted,VolumeSize]' --output text)"
  read -r snapshot_state snapshot_encrypted snapshot_size <<<"${snapshot}"
  [[ "${snapshot_state}" == 'completed' && "${snapshot_encrypted}" == 'True' && "${snapshot_size}" =~ ^[0-9]+$ && "${snapshot_size}" -ge 80 ]] || {
    echo '상태: Fail - EBS rehearsal snapshot은 completed/encrypted/80GiB 이상이어야 합니다.' >&2
    exit 1
  }
  volume_id="$(aws ec2 create-volume --region "${region}" --availability-zone "${availability_zone}" \
    --snapshot-id "${snapshot_id}" --volume-type gp3 --iops 3000 --throughput 125 \
    --tag-specifications "ResourceType=volume,Tags=[{Key=Name,Value=home-search-budget-production-recovery-${run_id}},{Key=Purpose,Value=budget-production-recovery-clone},{Key=RunId,Value=${run_id}},{Key=MaxLifetime,Value=4h}]" \
    --query VolumeId --output text)"
  [[ "${volume_id}" =~ ^vol-[0-9a-f]{8,17}$ ]]
  aws ec2 wait volume-available --region "${region}" --volume-ids "${volume_id}"
fi

cat >"${tmp_dir}/user-data.sh" <<'USERDATA'
#!/usr/bin/env bash
set -euo pipefail
shutdown -h +240
dnf install -y xfsprogs >/dev/null
systemctl enable --now docker
USERDATA

instance_id="$(aws ec2 run-instances --region "${region}" \
  --image-id "${ami_id}" --instance-type t3a.large \
  --iam-instance-profile "Name=${instance_profile}" \
  --network-interfaces "DeviceIndex=0,SubnetId=${subnet_id},Groups=${security_group_id},AssociatePublicIpAddress=true,DeleteOnTermination=true" \
  --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=80,VolumeType=gp3,Encrypted=true,DeleteOnTermination=true}' \
  --metadata-options 'HttpEndpoint=enabled,HttpTokens=required,HttpPutResponseHopLimit=1,InstanceMetadataTags=disabled' \
  --instance-initiated-shutdown-behavior terminate \
  --user-data "file://${tmp_dir}/user-data.sh" \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=home-search-budget-production-recovery-${run_id}},{Key=Purpose,Value=budget-production-recovery},{Key=RunId,Value=${run_id}},{Key=MaxLifetime,Value=4h}]" \
  --query 'Instances[0].InstanceId' --output text)"
[[ "${instance_id}" =~ ^i-[0-9a-f]{8,17}$ ]]
aws ec2 wait instance-status-ok --region "${region}" --instance-ids "${instance_id}"

if [[ "${mode}" == 'ebs' ]]; then
  aws ec2 attach-volume --region "${region}" --volume-id "${volume_id}" \
    --instance-id "${instance_id}" --device /dev/sdf >/dev/null
  aws ec2 wait volume-in-use --region "${region}" --volume-ids "${volume_id}"
fi

ssm_online=false
for _ in $(seq 1 60); do
  if [[ "$(aws ssm describe-instance-information --region "${region}" \
    --filters "Key=InstanceIds,Values=${instance_id}" --query 'InstanceInformationList[0].PingStatus' --output text)" == 'Online' ]]; then
    ssm_online=true
    break
  fi
  sleep 10
done
[[ "${ssm_online}" == 'true' ]] || {
  echo '상태: Fail - recovery instance가 SSM Online 상태가 되지 않았습니다.' >&2
  exit 1
}

registry="${backup_image%%/*}"
{
  echo "bash -s <<'RECOVERY'"
  printf "readonly RECOVERY_MODE='%s'\n" "${mode}"
  printf "readonly BACKUP_BUCKET='%s'\n" "${backup_bucket}"
  printf "readonly BACKUP_IMAGE='%s'\n" "${backup_image}"
  printf "readonly SNAPSHOT_VOLUME_ID='%s'\n" "${volume_id}"
  printf "readonly POSTGRES_IMAGE='%s'\n" "${postgres_image}"
  printf "readonly REGISTRY='%s'\n" "${registry}"
  cat <<'REMOTE'
set -Eeuo pipefail
set +x
install -d -m 0700 /recovery
aws ecr get-login-password --region ap-northeast-2 \
  | docker login --username AWS --password-stdin "${REGISTRY}" >/dev/null
if [[ "${RECOVERY_MODE}" == 'logical' ]]; then
docker pull "${BACKUP_IMAGE}" >/dev/null
for logical in property admin user ai; do
  key="$(aws s3api list-objects-v2 --bucket "${BACKUP_BUCKET}" --prefix "logical/${logical}-" \
    --query 'reverse(sort_by(Contents[?ends_with(Key, `.manifest.tsv`)], &LastModified))[0].Key' --output text)"
  [[ "${key}" != 'None' && "${key}" == logical/*.manifest.tsv ]]
  manifest="/recovery/${key##*/}"
  aws s3 cp "s3://${BACKUP_BUCKET}/${key}" "${manifest}" --only-show-errors
  dump="$(awk -F $'\t' '$1 == "dump_file" {print $2}' "${manifest}")"
  [[ "${dump}" =~ ^(property|admin|user|ai)-[0-9]{8}T[0-9]{6}Z[.]dump$ ]]
  aws s3 cp "s3://${BACKUP_BUCKET}/logical/${dump}" "/recovery/${dump}" --only-show-errors
  docker run --rm --network none --memory 6g --cpus 2 \
    -v /recovery:/recovery:ro "${BACKUP_IMAGE}" --verify-restore "${manifest}"
  rm -f "${manifest}" "/recovery/${dump}"
done
else
  [[ "${RECOVERY_MODE}" == 'ebs' && "${SNAPSHOT_VOLUME_ID}" =~ ^vol-[0-9a-f]{8,17}$ ]]
  device="/dev/disk/by-id/nvme-Amazon_Elastic_Block_Store_${SNAPSHOT_VOLUME_ID//-/}"
  for _ in $(seq 1 60); do [[ -e "${device}" ]] && break; sleep 2; done
  [[ -b "${device}" ]]
  dd if="${device}" of=/dev/null bs=16M status=progress
  install -d -m 0700 /recovery-data
  mount -o nouuid,nosuid,nodev "${device}" /recovery-data
  [[ -s /recovery-data/postgres/pgdata/PG_VERSION ]]
  docker pull "${POSTGRES_IMAGE}" >/dev/null
  docker run -d --name budget-recovery-postgres --network none --memory 6g --cpus 2 \
    --tmpfs /run/home-search-postgres-tls:rw,noexec,nosuid,size=16m \
    -e PGDATA=/var/lib/postgresql/data/pgdata \
    -v /recovery-data/postgres:/var/lib/postgresql/data "${POSTGRES_IMAGE}" >/dev/null
  ready=false
  for _ in $(seq 1 120); do
    if docker exec budget-recovery-postgres pg_isready -U home_search_bootstrap -d home_search >/dev/null 2>&1; then ready=true; break; fi
    sleep 2
  done
  [[ "${ready}" == 'true' ]]
  psql_local() { docker exec budget-recovery-postgres psql -X -At -v ON_ERROR_STOP=1 -U home_search_bootstrap "$@"; }
  [[ "$(psql_local -d postgres -c "SELECT count(*) FROM pg_database WHERE datname IN ('home_search','home_search_user','home_search_admin','home_search_ai')")" == '4' ]]
  [[ "$(psql_local -d home_search -c 'SELECT count(*) FROM public.flyway_schema_history WHERE NOT success')" == '0' ]]
  [[ "$(psql_local -d home_search_user -c 'SELECT count(*) FROM users.flyway_schema_history WHERE NOT success')" == '0' ]]
  [[ "$(psql_local -d home_search_admin -c 'SELECT count(*) FROM admin.flyway_schema_history WHERE NOT success')" == '0' ]]
  [[ "$(psql_local -d home_search -c 'SELECT count(*) FROM (SELECT source, source_key FROM public.trade GROUP BY source, source_key HAVING count(*) > 1) duplicate')" == '0' ]]
  [[ "$(psql_local -d home_search -c 'SELECT count(*) FROM public.trade t LEFT JOIN public.raw_trade_ingest r ON r.id=t.raw_ingest_id WHERE r.id IS NULL')" == '0' ]]
  [[ "$(psql_local -d home_search -c "SELECT count(*) FROM public.raw_trade_ingest r LEFT JOIN public.trade_match_evidence e ON e.raw_ingest_id=r.id WHERE r.status='MATCH_FAILED' AND e.id IS NULL")" == '0' ]]
  [[ "$(psql_local -d home_search -c 'SELECT count(*) FROM public.map_marker_active_generation')" == '1' ]]
  [[ "$(psql_local -d home_search -c 'SELECT count(*) FROM public.map_marker_active_generation a JOIN public.map_marker_generation g ON g.id=a.generation_id WHERE g.status = '\''ACTIVE'\'' AND g.complex_marker_count=(SELECT count(*) FROM public.map_complex_marker_projection p WHERE p.generation_id=a.generation_id) AND g.region_marker_count=(SELECT count(*) FROM public.map_region_marker_projection p WHERE p.generation_id=a.generation_id)')" == '1' ]]
  docker stop --time 60 budget-recovery-postgres >/dev/null
  docker rm budget-recovery-postgres >/dev/null
  umount /recovery-data
  xfs_repair -n "${device}"
fi
printf '{"metric":"restore_rehearsal_success","mode":"%s","value":1}\n' "${RECOVERY_MODE}"
REMOTE
  echo 'RECOVERY'
} >"${tmp_dir}/remote-command.sh"
jq -Rs '{commands:[.]}' "${tmp_dir}/remote-command.sh" >"${tmp_dir}/commands.json"

command_id="$(aws ssm send-command --region "${region}" \
  --instance-ids "${instance_id}" --document-name AWS-RunShellScript \
  --comment "budget-production restore rehearsal ${run_id}" \
  --timeout-seconds 14400 --parameters "file://${tmp_dir}/commands.json" \
  --output-s3-bucket-name "${backup_bucket}" \
  --output-s3-key-prefix "restore-evidence/${run_id}" \
  --cloud-watch-output-config 'CloudWatchOutputEnabled=true,CloudWatchLogGroupName=/home-search/budget-production/recovery' \
  --query 'Command.CommandId' --output text)"
[[ "${command_id}" =~ ^[0-9a-f-]{36}$ ]]

status='Pending'
for _ in $(seq 1 960); do
  status="$(aws ssm get-command-invocation --region "${region}" --command-id "${command_id}" \
    --instance-id "${instance_id}" --query Status --output text 2>/dev/null || true)"
  case "${status}" in
    Success) break ;;
    Failed|Cancelled|TimedOut|Cancelling) break ;;
  esac
  sleep 15
done
[[ "${status}" == 'Success' ]] || {
  echo "상태: Fail - restore rehearsal SSM command 상태: ${status}" >&2
  exit 1
}

evidence_key="restore-evidence/${run_id}/${command_id}/${instance_id}/awsrunShellScript/0.awsrunShellScript/stdout"
evidence_size="$(aws s3api head-object --region "${region}" --bucket "${backup_bucket}" \
  --key "${evidence_key}" --query ContentLength --output text)"
[[ "${evidence_size}" =~ ^[1-9][0-9]*$ ]] || {
  echo '상태: Fail - restore rehearsal SSM evidence 업로드를 확인하지 못했습니다.' >&2
  exit 1
}

echo "상태: Pass - restore rehearsal을 완료했습니다: run_id=${run_id} instance_id=${instance_id}"
