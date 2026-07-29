#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

manifest="${1:?release manifest is required}"
migration_uri="${2:?migration artifact S3 URI is required}"
migration_sha256="${3:?migration manifest SHA-256 is required}"
output="${4:?Terraform variable output path is required}"

[[ -f "${manifest}" && ! -L "${manifest}" ]] || {
  echo '상태: Fail - release manifest는 symlink가 아닌 regular file이어야 합니다.' >&2
  exit 1
}
[[ "${migration_uri}" =~ ^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/[A-Za-z0-9][A-Za-z0-9._/-]*$ ]]
[[ "${migration_sha256}" =~ ^[0-9a-f]{64}$ ]]
[[ ! -e "${output}" && ! -L "${output}" && -d "$(dirname "${output}")" && ! -L "$(dirname "${output}")" ]] || {
  echo '상태: Fail - Terraform variable output은 안전한 경로의 새 파일이어야 합니다.' >&2
  exit 1
}

expected_applications='["admin-api","admin-gateway","admin-migration","admin-ops","ai","backup","chat-bff","ml","ops-bootstrap","property-api","property-batch","property-flyway","public-gateway","source-data-migration","user-api","user-flyway","user-insight-worker"]'
expected_platform='["budget-postgres","budget-valkey"]'
jq -e --argjson applications "${expected_applications}" --argjson platform "${expected_platform}" '
  .format_version == 2
  and (.tag | test("^v[0-9]+[.][0-9]+[.][0-9]+$"))
  and .tag != "v1.0.4"
  and (.commit_sha | test("^[0-9a-f]{40}$"))
  and .build_architecture == "linux/amd64"
  and .vulnerability_critical_gate_passed == true
  and .vulnerability_policy_gate_passed == true
  and ((.images | keys | sort) == $applications)
  and ((.platform_images | keys | sort) == $platform)
  and all((.images + .platform_images) | to_entries[];
    .value.repository == ("home-search/" + .key)
    and (.value.digest | test("^sha256:[0-9a-f]{64}$"))
    and (.value.uri | test("^[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/[a-z0-9-]+@sha256:[0-9a-f]{64}$"))
    and .value.uri == ((.value.uri | split("/")[0]) + "/" + .value.repository + "@" + .value.digest))
' "${manifest}" >/dev/null || {
  echo '상태: Fail - budget-production release는 v1.0.4가 아닌 완전한 17+2 amd64 digest release여야 합니다.' >&2
  exit 1
}

temporary="$(mktemp "$(dirname "${output}")/.budget-release-tfvars.XXXXXX")"
cleanup() { unlink "${temporary}" 2>/dev/null || true; }
trap cleanup EXIT
jq --arg migration_uri "${migration_uri%/}" --arg migration_sha256 "${migration_sha256}" '
  {
    image_uris:(.images | with_entries(.value = .value.uri)),
    platform_image_uris:(.platform_images | with_entries(.value = .value.uri)),
    deployment_release_tag:.tag,
    migration_artifact_s3_uri:$migration_uri,
    migration_manifest_sha256:$migration_sha256
  }
' "${manifest}" >"${temporary}"
chmod 0600 "${temporary}"
mv "${temporary}" "${output}"
trap - EXIT
echo '상태: Pass - budget-production 17+2 release를 secret 없는 Terraform inputs로 변환했습니다.'
