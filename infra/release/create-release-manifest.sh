#!/usr/bin/env bash
set -Eeuo pipefail

tag="${1:?tag is required}"
commit_sha="${2:?commit SHA is required}"
registry="${3:?ECR registry is required}"
output="${4:?output path is required}"
[[ "${tag}" =~ ^v[0-9]+[.][0-9]+[.][0-9]+$ && "${commit_sha}" =~ ^[0-9a-f]{40}$ ]]

application_images=(
  property-api property-batch property-flyway admin-api admin-migration admin-ops
  user-api user-insight-worker user-flyway source-data-migration public-gateway admin-gateway backup ops-bootstrap ml
  ai chat-bff
)
platform_images=(budget-postgres budget-valkey)
application_tmp="$(mktemp)"
platform_tmp="$(mktemp)"
cleanup() {
  unlink "${application_tmp}" "${application_tmp}.next" "${platform_tmp}" "${platform_tmp}.next" 2>/dev/null || true
}
trap cleanup EXIT
printf '{}\n' >"${application_tmp}"
printf '{}\n' >"${platform_tmp}"

append_image() {
  local image="$1"
  local target="$2"
  repository="home-search/${image}"
  digest="$(aws ecr describe-images --repository-name "${repository}" \
    --image-ids imageTag="${commit_sha}" --query 'imageDetails[0].imageDigest' --output text)"
  semver_digest="$(aws ecr describe-images --repository-name "${repository}" \
    --image-ids imageTag="${tag#v}" --query 'imageDetails[0].imageDigest' --output text)"
  [[ "${digest}" =~ ^sha256:[0-9a-f]{64}$ && "${semver_digest}" == "${digest}" ]] \
    || { echo "상태: Fail - ${image} SHA/SemVer digest가 일치하지 않습니다." >&2; exit 1; }
  jq --arg name "${image}" --arg repository "${repository}" --arg digest "${digest}" \
    --arg uri "${registry}/${repository}@${digest}" \
    '. + {($name): {repository:$repository,digest:$digest,uri:$uri}}' "${target}" >"${target}.next"
  mv "${target}.next" "${target}"
}

for image in "${application_images[@]}"; do
  append_image "${image}" "${application_tmp}"
done
for image in "${platform_images[@]}"; do
  append_image "${image}" "${platform_tmp}"
done

jq -n --arg tag "${tag}" --arg commit_sha "${commit_sha}" \
  --arg generated_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --slurpfile images "${application_tmp}" --slurpfile platform_images "${platform_tmp}" \
  '{format_version:1,tag:$tag,commit_sha:$commit_sha,generated_at:$generated_at,images:$images[0],platform_images:$platform_images[0]}' >"${output}"
jq -e '
  .format_version == 1 and (.images | length == 17) and (.platform_images | length == 2)
  and ([.images[].digest, .platform_images[].digest] | all(test("^sha256:[0-9a-f]{64}$")))
' "${output}" >/dev/null
