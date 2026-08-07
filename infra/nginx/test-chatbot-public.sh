#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
nginx_image="${NGINX_IMAGE:-nginx:1.27-alpine}"
suffix="${RANDOM}-$$"
network="home-search-chatbot-nginx-test-${suffix}"
property_upstream="home-search-chatbot-property-upstream-${suffix}"
chatbot_upstream="home-search-chatbot-bff-upstream-${suffix}"
gateway="home-search-chatbot-nginx-gateway-${suffix}"
tmp_dir="$(mktemp -d)"

[[ "$(grep -Fc 'proxy_read_timeout 75s;' "$script_dir/property-chatbot-public.conf")" == "2" ]]

cleanup() {
    docker stop --time 1 "$gateway" "$chatbot_upstream" "$property_upstream" >/dev/null 2>&1 || true
    docker network remove "$network" >/dev/null 2>&1 || true
    unlink "$tmp_dir/headers" 2>/dev/null || true
    unlink "$tmp_dir/body" 2>/dev/null || true
    rmdir "$tmp_dir" 2>/dev/null || true
}
trap cleanup EXIT

docker network create "$network" >/dev/null
docker run --rm --detach --name "$property_upstream" \
    --network "$network" --network-alias api \
    --volume "$script_dir/property-public-test-upstream.conf:/etc/nginx/conf.d/default.conf:ro" \
    "$nginx_image" >/dev/null
docker run --rm --detach --name "$chatbot_upstream" \
    --network "$network" --network-alias chat-bff \
    --volume "$script_dir/chatbot-public-test-upstream.conf:/etc/nginx/conf.d/default.conf:ro" \
    "$nginx_image" >/dev/null
docker run --rm --detach --name "$gateway" \
    --network "$network" \
    --publish 127.0.0.1::8080 \
    --env FRONTEND_URL=http://localhost:5173 \
    --volume "$script_dir/property-chatbot-public.conf:/etc/nginx/templates/default.conf.template:ro" \
    "$nginx_image" >/dev/null

host_port="$(docker inspect --format='{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}' "$gateway")"
base_url="http://127.0.0.1:${host_port}"
request_id="b8f12b67-0369-4e4a-bf5f-ce8af0315386"

ready=false
for _ in $(seq 1 30); do
    status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
        --max-time 2 --header 'Content-Type: application/json' --data '{}' \
        "${base_url}/api/v1/map/regions" || true)"
    if [[ "$status" == "200" ]]; then
        ready=true
        break
    fi
    sleep 0.2
done
if [[ "$ready" != "true" ]]; then
    echo "상태: Fail - chatbot public gateway가 준비되지 않았습니다." >&2
    docker logs "$gateway" >&2
    exit 1
fi

json_status="$(curl --silent --show-error --dump-header "$tmp_dir/headers" \
    --output "$tmp_dir/body" --write-out '%{http_code}' --max-time 5 \
    --header 'Origin: http://localhost:5173' \
    --header 'Authorization: Bearer test-token' \
    --header "X-Request-Id: ${request_id}" \
    --header 'Content-Type: application/json' \
    --data '{"question":"test"}' "${base_url}/api/v1/chatbot/query")"
[[ "$json_status" == "200" ]]
grep -Eqi '^Access-Control-Allow-Origin: http://localhost:5173' "$tmp_dir/headers"
grep -Eqi '^Vary: Origin' "$tmp_dir/headers"
grep -Eq '"upstream":"chat-bff-json"' "$tmp_dir/body"
grep -Eq "\"requestId\":\"${request_id}\"" "$tmp_dir/body"

stream_status="$(curl --silent --show-error --dump-header "$tmp_dir/headers" \
    --output "$tmp_dir/body" --write-out '%{http_code}' --max-time 5 \
    --header 'Origin: http://localhost:5173' \
    --header 'Authorization: Bearer test-token' \
    --header "X-Request-Id: ${request_id}" \
    --header 'Content-Type: application/json' \
    --data '{"question":"test"}' "${base_url}/api/v1/chatbot/query/stream")"
[[ "$stream_status" == "200" ]]
grep -Eqi '^Content-Type: text/event-stream' "$tmp_dir/headers"
grep -Eq '^event: final' "$tmp_dir/body"
grep -Eq '"upstream":"chat-bff-sse"' "$tmp_dir/body"

options_status="$(curl --silent --show-error --dump-header "$tmp_dir/headers" \
    --output /dev/null --write-out '%{http_code}' --max-time 5 --request OPTIONS \
    --header 'Origin: http://localhost:5173' \
    --header 'Access-Control-Request-Method: POST' \
    --header 'Access-Control-Request-Headers: authorization,content-type,x-request-id' \
    "${base_url}/api/v1/chatbot/query")"
[[ "$options_status" == "204" ]]
grep -Eqi '^Access-Control-Allow-Origin: http://localhost:5173' "$tmp_dir/headers"
grep -Eqi '^Access-Control-Allow-Methods: POST, OPTIONS' "$tmp_dir/headers"
grep -Eqi '^Access-Control-Allow-Headers: Authorization, Content-Type, X-Request-Id' "$tmp_dir/headers"

for blocked_path in \
    /internal/v1/chatbot \
    /actuator/health \
    /api/v1/admin/chatbot \
    /api/v1/chatbot/query/extra; do
    blocked_status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
        --max-time 5 "${base_url}${blocked_path}")"
    if [[ "$blocked_status" != "404" ]]; then
        echo "상태: Fail - ${blocked_path}는 404여야 합니다: ${blocked_status}" >&2
        exit 1
    fi
done

echo "상태: Pass - property 회귀, chatbot JSON/SSE, CORS, 차단 경로를 확인했습니다."
