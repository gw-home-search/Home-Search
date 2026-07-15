#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
nginx_image="${NGINX_IMAGE:-nginx:1.27-alpine}"
suffix="${RANDOM}-$$"
network="home-search-nginx-test-${suffix}"
upstream="home-search-nginx-upstream-${suffix}"
gateway="home-search-nginx-gateway-${suffix}"
tmp_dir="$(mktemp -d)"

cleanup() {
    docker stop --time 1 "$gateway" "$upstream" >/dev/null 2>&1 || true
    docker network remove "$network" >/dev/null 2>&1 || true
    unlink "$tmp_dir/headers" 2>/dev/null || true
    unlink "$tmp_dir/body" 2>/dev/null || true
    rmdir "$tmp_dir" 2>/dev/null || true
}
trap cleanup EXIT

docker network create "$network" >/dev/null
docker run --rm --detach --name "$upstream" \
    --network "$network" --network-alias api \
    --volume "$script_dir/property-public-test-upstream.conf:/etc/nginx/conf.d/default.conf:ro" \
    "$nginx_image" >/dev/null
docker run --rm --detach --name "$gateway" \
    --network "$network" \
    --publish 127.0.0.1::8080 \
    --env FRONTEND_URL=http://localhost:5173 \
    --volume "$script_dir/property-public.conf:/etc/nginx/templates/default.conf.template:ro" \
    "$nginx_image" >/dev/null

host_port="$(docker inspect --format='{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}' "$gateway")"
region_endpoint="http://127.0.0.1:${host_port}/api/v1/map/regions"
complex_endpoint="http://127.0.0.1:${host_port}/api/v1/map/complexes"
region_request='{"swLat":33.0,"swLng":124.0,"neLat":39.0,"neLng":132.0,"region":"si-do"}'
complex_request='{"swLat":37.4,"swLng":126.8,"neLat":37.7,"neLng":127.2}'

ready=false
for _ in $(seq 1 30); do
    status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
        --max-time 2 --header 'Content-Type: application/json' --data "$region_request" \
        "$region_endpoint" || true)"
    if [[ "$status" == "200" ]]; then
        ready=true
        break
    fi
    sleep 0.2
done
if [[ "$ready" != "true" ]]; then
    echo "상태: Fail - nginx gateway가 준비되지 않았습니다." >&2
    docker logs "$gateway" >&2
    exit 1
fi

burst_codes="$(seq 1 60 | xargs -I{} -P20 curl --silent --show-error \
    --output /dev/null --write-out '%{http_code}\n' --max-time 5 \
    --header 'Content-Type: application/json' --data "$region_request" "$region_endpoint")"
success_count="$(printf '%s\n' "$burst_codes" | grep -c '^200$' || true)"
rate_limited_count="$(printf '%s\n' "$burst_codes" | grep -c '^429$' || true)"
if (( success_count == 0 || rate_limited_count == 0 )); then
    echo "상태: Fail - burst에서 200과 429가 모두 관찰되어야 합니다." >&2
    echo "200=${success_count}, 429=${rate_limited_count}" >&2
    exit 1
fi

rate_status="$(curl --silent --show-error --dump-header "$tmp_dir/headers" \
    --output "$tmp_dir/body" --write-out '%{http_code}' --max-time 5 \
    --header 'Content-Type: application/json' --header 'Origin: http://localhost:5173' \
    --data "$complex_request" "$complex_endpoint")"
if [[ "$rate_status" != "429" ]]; then
    echo "상태: Fail - saturated 요청이 429를 반환하지 않았습니다: ${rate_status}" >&2
    exit 1
fi

grep -Eqi '^Content-Type: application/problem\+json' "$tmp_dir/headers"
grep -Eqi '^Retry-After: 1' "$tmp_dir/headers"
grep -Eqi '^Cache-Control: no-store' "$tmp_dir/headers"
grep -Eqi '^Access-Control-Allow-Origin: http://localhost:5173' "$tmp_dir/headers"
grep -Eqi '^Vary: Origin' "$tmp_dir/headers"
grep -Eq '"type":"/docs/index.html#error-code-list"' "$tmp_dir/body"
grep -Eq '"title":"Too Many Requests"' "$tmp_dir/body"
grep -Eq '"status":429' "$tmp_dir/body"
grep -Eq '"detail":"Too many requests\."' "$tmp_dir/body"
grep -Eq '"exception":"IngressRateLimitException"' "$tmp_dir/body"
grep -Eq '"timestamp":"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(Z|\+00:00)"' "$tmp_dir/body"

options_status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
    --max-time 5 --request OPTIONS "$region_endpoint")"
if [[ "$options_status" != "200" ]]; then
    echo "상태: Fail - OPTIONS 요청은 rate limit에서 제외되어야 합니다: ${options_status}" >&2
    exit 1
fi

sleep 4
recovered_status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
    --max-time 5 --header 'Content-Type: application/json' --data "$complex_request" \
    "$complex_endpoint")"
if [[ "$recovered_status" != "200" ]]; then
    echo "상태: Fail - cooldown 뒤 요청이 복구되지 않았습니다: ${recovered_status}" >&2
    exit 1
fi

echo "상태: Pass - map bbox rate limit, ProblemDetail, OPTIONS 예외, cooldown 복구를 확인했습니다."
