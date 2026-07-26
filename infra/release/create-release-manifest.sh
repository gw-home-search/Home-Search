#!/usr/bin/env bash
set -Eeuo pipefail

tag="${1:?tag is required}"
commit_sha="${2:?commit SHA is required}"
registry="${3:?ECR registry is required}"
output="${4:?output path is required}"
[[ "${tag}" =~ ^v[0-9]+[.][0-9]+[.][0-9]+$ && "${commit_sha}" =~ ^[0-9a-f]{40}$ ]]

images=(
  property-api property-batch property-flyway admin-api admin-migration admin-ops
  user-api user-insight-worker user-flyway source-data-migration public-gateway admin-gateway backup ops-bootstrap ml
)
tmp="$(mktemp)"
cleanup() { unlink "${tmp}" 2>/dev/null || true; unlink "${tmp}.next" 2>/dev/null || true; }
trap cleanup EXIT
printf '{}\n' >"${tmp}"

for image in "${images[@]}"; do
  repository="home-search/${image}"
  digest="$(aws ecr describe-images --repository-name "${repository}" \
    --image-ids imageTag="${commit_sha}" --query 'imageDetails[0].imageDigest' --output text)"
  semver_digest="$(aws ecr describe-images --repository-name "${repository}" \
    --image-ids imageTag="${tag#v}" --query 'imageDetails[0].imageDigest' --output text)"
  [[ "${digest}" =~ ^sha256:[0-9a-f]{64}$ && "${semver_digest}" == "${digest}" ]] \
    || { echo "상태: Fail - ${image} SHA/SemVer digest가 일치하지 않습니다." >&2; exit 1; }
  jq --arg name "${image}" --arg repository "${repository}" --arg digest "${digest}" \
    --arg uri "${registry}/${repository}@${digest}" \
    '. + {($name): {repository:$repository,digest:$digest,uri:$uri}}' "${tmp}" >"${tmp}.next"
  mv "${tmp}.next" "${tmp}"
done

jq -n --arg tag "${tag}" --arg commit_sha "${commit_sha}" \
  --arg generated_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --slurpfile images "${tmp}" \
  '{format_version:1,tag:$tag,commit_sha:$commit_sha,generated_at:$generated_at,images:$images[0]}' >"${output}"
jq -e '.format_version == 1 and (.images | length == 15) and ([.images[].digest] | all(test("^sha256:[0-9a-f]{64}$")))' "${output}" >/dev/null
