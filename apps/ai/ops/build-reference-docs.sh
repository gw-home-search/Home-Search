#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ai_root="$(cd "${script_dir}/.." && pwd)"
repo_root="$(cd "${ai_root}/../.." && pwd)"
build_root="${ai_root}/build/reference-docs"
image="asciidoctor/docker-asciidoctor@sha256:76b73afb7249f66745583e34f0953cf024dc47ec739e29218ce6c1b42f3589ea"
check_only=false
if [[ "${1:-}" == "--check" ]]; then
    check_only=true
elif [[ -n "${1:-}" ]]; then
    echo '상태: Fail - 지원하지 않는 reference docs option입니다.' >&2
    exit 2
fi

tmp_dir="$(mktemp -d)"
cleanup() {
    find "$tmp_dir" -type f -exec unlink {} \; 2>/dev/null || true
    find "$tmp_dir" -depth -type d -exec rmdir {} \; 2>/dev/null || true
}
trap cleanup EXIT

generate() {
    local destination="$1"
    python3 "${script_dir}/generate-reference-docs.py" \
        --config "${ai_root}/config/reference_sources.toml" \
        --examples "${ai_root}/docs/examples" \
        --output "${destination}/generated-snippets"
}

generate "${tmp_dir}/first"
generate "${tmp_dir}/second"
diff -ru "${tmp_dir}/first" "${tmp_dir}/second"
if rg -n -i '(servicekey|api[_-]?key|secret|password)[=:][^<[:space:]]' "${tmp_dir}/first"; then
    echo '상태: Fail - generated reference docs에 secret pattern이 있습니다.' >&2
    exit 1
fi
for source in edu.school-location edu.academy-registry place.sbiz-academy retail.large-store transport.rail-station; do
    test -f "${tmp_dir}/first/generated-snippets/${source}/failure-codes.adoc"
done
rail_request="${tmp_dir}/first/generated-snippets/transport.rail-station/download-request.adoc"
if ! rg -q '^GET https://data\.kric\.go\.kr/rips/dataset/download\.file\?type=filedata&id=32&operation=1$' "$rail_request"; then
    echo '상태: Fail - 검증된 rail release URL이 문서와 일치하지 않습니다.' >&2
    exit 1
fi
if rg -q '^GET https://www\.data\.go\.kr/data/15013205/standard\.do$' "$rail_request"; then
    echo '상태: Fail - rail landing URL은 download request가 아닙니다.' >&2
    exit 1
fi
retail_request="${tmp_dir}/first/generated-snippets/retail.large-store/http-request.adoc"
if ! rg -q '^GET https://apis\.data\.go\.kr/1741000/large_scale_retail_stores/info$' "$retail_request"; then
    echo '상태: Fail - 검증된 retail API request가 문서와 일치하지 않습니다.' >&2
    exit 1
fi

mkdir -p "$build_root"
find "$build_root" -type f -exec unlink {} \; 2>/dev/null || true
find "$build_root" -depth -mindepth 1 -type d -exec rmdir {} \; 2>/dev/null || true
cp -R "${tmp_dir}/first/generated-snippets" "$build_root/generated-snippets"
cp "${tmp_dir}/first/manifest.json" "${tmp_dir}/first/SHA256SUMS" "$build_root/"
mkdir -p "$build_root/html"
docker run --rm \
    --volume "${repo_root}:/documents:ro" \
    --volume "${build_root}/html:/output" \
    "$image" asciidoctor \
    -a "snippets=/documents/apps/ai/build/reference-docs/generated-snippets" \
    -D /output /documents/apps/ai/docs/asciidoc/index.adoc

test -f "$build_root/html/index.html"
if rg -n 'include::' "$build_root/html"; then
    echo '상태: Fail - reference docs include가 완성되지 않았습니다.' >&2
    exit 1
fi
if [[ "$check_only" == true ]]; then
    echo '상태: Pass - reference docs 결정성·HTML·secret 검증을 통과했습니다.'
else
    echo "상태: Pass - ${build_root}/html/index.html"
fi
