#!/usr/bin/env bash
set -Eeuo pipefail

manifest="${1:?release manifest is required}"
adot_image_uri="${2:?ADOT collector image URI is required}"
output="${3:?Terraform variable output path is required}"

[[ -f "${manifest}" && ! -L "${manifest}" ]] || {
  echo '상태: Fail - release manifest는 symlink가 아닌 regular file이어야 합니다.' >&2
  exit 1
}
if [[ -L "${output}" ]]; then
  echo '상태: Fail - Terraform variable output symlink는 허용하지 않습니다.' >&2
  exit 1
fi
output_directory="$(dirname "${output}")"
[[ -d "${output_directory}" && ! -L "${output_directory}" ]] || {
  echo '상태: Fail - Terraform variable output directory가 안전하지 않습니다.' >&2
  exit 1
}

images=(
  property-api property-batch property-flyway admin-api admin-migration admin-ops
  user-api user-insight-worker user-flyway source-data-migration public-gateway admin-gateway
  backup ops-bootstrap ml ai chat-bff
)
expected_images="$(printf '%s\n' "${images[@]}" | jq -Rsc 'split("\n")[:-1] | sort')"
adot_pattern='^(public[.]ecr[.]aws/aws-observability/aws-otel-collector|[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/aws-otel-collector)@sha256:[0-9a-f]{64}$'
[[ "${adot_image_uri}" =~ ${adot_pattern} ]] || {
  echo '상태: Fail - ADOT collector image는 approved repository의 immutable digest여야 합니다.' >&2
  exit 1
}

jq -e --argjson expected "${expected_images}" '
  .format_version == 2
  and (.tag | test("^v[0-9]+[.][0-9]+[.][0-9]+$"))
  and (.commit_sha | test("^[0-9a-f]{40}$"))
  and .build_architecture == "linux/amd64"
  and (.event_schema_sha256 | test("^[0-9a-f]{64}$"))
  and (.topic_manifest_sha256 | test("^[0-9a-f]{64}$"))
  and (.flyway_migration_set_sha256 | test("^[0-9a-f]{64}$"))
  and (.sbom_set_sha256 | test("^[0-9a-f]{64}$"))
  and (.vulnerability_set_sha256 | test("^[0-9a-f]{64}$"))
  and (.build_flags.market_news_enabled | type == "boolean")
  and .vulnerability_critical_gate_passed == true
  and .vulnerability_policy_gate_passed == true
  and ((.images | keys | sort) == $expected)
  and ([$expected[] as $name |
    .images[$name] as $image
    | $image.repository == ("home-search/" + $name)
    and ($image.digest | test("^sha256:[0-9a-f]{64}$"))
    and ($image.uri | test("^[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/[a-z0-9-]+@sha256:[0-9a-f]{64}$"))
    and $image.uri == (($image.uri | split("/")[0]) + "/" + $image.repository + "@" + $image.digest)
  ] | all)
' "${manifest}" >/dev/null || {
  echo '상태: Fail - release manifest metadata 또는 17-image digest set이 유효하지 않습니다.' >&2
  exit 1
}

temporary="$(mktemp "${output_directory}/.release-auto-tfvars.XXXXXX")"
cleanup() { unlink "${temporary}" 2>/dev/null || true; }
trap cleanup EXIT
jq --arg adot "${adot_image_uri}" '
  {
    image_uris:(.images | with_entries(.value = .value.uri)),
    adot_collector_image_uri:$adot,
    deployment_release_tag:.tag
  }
' "${manifest}" >"${temporary}"
chmod 0600 "${temporary}"
mv "${temporary}" "${output}"
trap - EXIT
echo '상태: Pass - immutable release manifest를 Terraform image inputs로 변환했습니다.'
