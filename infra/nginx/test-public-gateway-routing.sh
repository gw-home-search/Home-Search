#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
nginx_image="${NGINX_IMAGE:-nginx:1.27-alpine}"
nginx_config="${script_dir}/chainguard-nginx.conf"
grep -Fq 'log_format home_search_json escape=json' "${nginx_config}"
grep -Fq '"uri":"$uri"' "${nginx_config}"
grep -Fq 'access_log /dev/stdout home_search_json;' "${nginx_config}"
! grep -Fq '$request_uri' "${nginx_config}"
! grep -Fq '"request":"$request"' "${nginx_config}"
suffix="${RANDOM}-$$"
network="home-search-public-gateway-test-${suffix}"
property_upstream="home-search-property-upstream-${suffix}"
user_upstream="home-search-user-upstream-${suffix}"
chatbot_upstream="home-search-chatbot-upstream-${suffix}"
seo_upstream="home-search-seo-upstream-${suffix}"
gateway="home-search-public-gateway-${suffix}"

cleanup() {
    docker stop --time 1 "$gateway" "$property_upstream" "$user_upstream" "$chatbot_upstream" "$seo_upstream" >/dev/null 2>&1 || true
    docker network remove "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create "$network" >/dev/null
docker run --rm --detach --name "$property_upstream" \
    --network "$network" --network-alias property-api \
    --volume "$script_dir/public-gateway-property-test-upstream.conf:/etc/nginx/conf.d/default.conf:ro" \
    "$nginx_image" >/dev/null
docker run --rm --detach --name "$user_upstream" \
    --network "$network" --network-alias user-api \
    --volume "$script_dir/public-gateway-user-test-upstream.conf:/etc/nginx/conf.d/default.conf:ro" \
    "$nginx_image" >/dev/null
docker run --rm --detach --name "$chatbot_upstream" \
    --network "$network" --network-alias chat-bff \
    --volume "$script_dir/chatbot-public-test-upstream.conf:/etc/nginx/conf.d/default.conf:ro" \
    "$nginx_image" >/dev/null
docker run --rm --detach --name "$seo_upstream" \
    --network "$network" --network-alias seo-renderer \
    --volume "$script_dir/public-gateway-seo-test-upstream.conf:/etc/nginx/conf.d/default.conf:ro" \
    "$nginx_image" >/dev/null
docker run --rm --detach --name "$gateway" \
    --network "$network" \
    --publish 127.0.0.1::8080 \
    --env PROPERTY_API_HOST=property-api \
    --env PROPERTY_API_PORT=8080 \
    --env USER_API_HOST=user-api \
    --env USER_API_PORT=8080 \
    --env CHAT_BFF_HOST=chat-bff \
    --env CHAT_BFF_PORT=8083 \
    --env SEO_RENDERER_HOST=seo-renderer \
    --env SEO_RENDERER_PORT=3000 \
    --volume "$script_dir/public-gateway.conf.template:/etc/nginx/templates/default.conf.template:ro" \
    --volume "$script_dir/../../apps/web/public/google-analytics-consent.js:/usr/share/nginx/html/google-analytics-consent.js:ro" \
    "$nginx_image" >/dev/null

host_port="$(docker inspect --format='{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}' "$gateway")"
base_url="http://127.0.0.1:${host_port}"

ready=false
for _ in $(seq 1 30); do
    status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
        --max-time 2 "${base_url}/api/v1/insights/trades/latest" || true)"
    if [[ "$status" == "200" ]]; then
        ready=true
        break
    fi
    sleep 0.2
done
if [[ "$ready" != "true" ]]; then
    echo "상태: Fail - public gateway가 준비되지 않았습니다." >&2
    docker logs "$gateway" >&2
    exit 1
fi

assert_route() {
    local path="$1"
    local expected_status="$2"
    local expected_upstream="${3:-}"
    local authorization="${4:-}"
    local body_file
    body_file="$(mktemp)"

    local curl_args=(
        --silent
        --show-error
        --output "$body_file"
        --write-out '%{http_code}'
        --max-time 5
    )
    if [[ -n "$authorization" ]]; then
        curl_args+=(--header "Authorization: ${authorization}")
    fi

    local actual_status
    actual_status="$(curl "${curl_args[@]}" "${base_url}${path}")"
    if [[ "$actual_status" != "$expected_status" ]]; then
        echo "상태: Fail - ${path} 상태가 ${expected_status}여야 합니다: ${actual_status}" >&2
        rm -f "$body_file"
        exit 1
    fi
    if [[ -n "$expected_upstream" ]] && ! grep -Fq "\"upstream\":\"${expected_upstream}\"" "$body_file"; then
        echo "상태: Fail - ${path}가 ${expected_upstream}로 전달되지 않았습니다." >&2
        rm -f "$body_file"
        exit 1
    fi
    rm -f "$body_file"
}

for path in \
    /api/v1/insights/trades/latest \
    /api/v1/insights/trades/weekly \
    /api/v1/insights/trends \
    /api/v1/insights/news; do
    assert_route "$path" 200 property
done

for path in \
    /api/v1/insights/subscription \
    /api/v1/insights/inbox; do
    assert_route "$path" 401
    assert_route "$path" 200 user "Bearer route-test"
done

assert_route /api/v1/chatbot/query 200 chat-bff-json "Bearer route-test"
assert_route /api/v1/chatbot/query/stream 200 chat-bff-sse "Bearer route-test"

robots_content_type="$(curl --silent --show-error --head "${base_url}/robots.txt" | tr -d '\r' | awk -F': ' 'tolower($1)=="content-type"{print tolower($2)}')"
[[ "${robots_content_type}" == text/plain* ]] || { echo "상태: Fail - robots.txt Content-Type이 text/plain이 아닙니다." >&2; exit 1; }
assert_route /sitemap.xml 200
assert_route /sitemaps/pages.xml 200
assert_route /complexes/501 200
assert_route /regions/1 200
assert_route /privacy 200
assert_route /terms 200
assert_route /about 200
analytics_headers="$(curl --silent --show-error --head "${base_url}/google-analytics-consent.js" | tr -d '\r')"
grep -Fiq 'HTTP/1.1 200' <<<"${analytics_headers}"
grep -Eiq '^Content-Type: (application|text)/javascript' <<<"${analytics_headers}"
grep -Fiq 'Cache-Control: no-cache' <<<"${analytics_headers}"
assert_route /complexes/999 404
curl --silent --show-error --head "${base_url}/complexes/invalid" | tr -d '\r' | grep -Fiq 'X-Robots-Tag: noindex, nofollow'
ordinary_body="$(curl --silent --show-error "${base_url}/complexes/501")"
bot_body="$(curl --silent --show-error --user-agent 'Googlebot' "${base_url}/complexes/501")"
[[ "${ordinary_body}" == "${bot_body}" ]] || { echo "상태: Fail - User-Agent별 SEO 본문이 다릅니다." >&2; exit 1; }
curl --silent --show-error --head "${base_url}/my/favorites" | tr -d '\r' | grep -Fiq 'X-Robots-Tag: noindex, nofollow'
assert_route /not-registered 404
curl --silent --show-error --head "${base_url}/not-registered" | tr -d '\r' | grep -Fiq 'X-Robots-Tag: noindex, nofollow'

oauth_headers="$(curl --silent --show-error --max-time 5 \
    --header 'Host: staging.homesearch.world' \
    --header 'X-Forwarded-Proto: https' \
    "${base_url}/oauth2/authorization/kakao")"
if ! grep -Fq '"forwardedProto":"https"' <<<"${oauth_headers}"; then
    echo "상태: Fail - OAuth callback 생성을 위한 외부 HTTPS protocol이 보존되지 않았습니다: ${oauth_headers}" >&2
    exit 1
fi
if ! grep -Fq '"host":"staging.homesearch.world"' <<<"${oauth_headers}"; then
    echo "상태: Fail - OAuth callback 생성을 위한 외부 host가 보존되지 않았습니다: ${oauth_headers}" >&2
    exit 1
fi

invalid_oauth_headers="$(curl --silent --show-error --max-time 5 \
    --header 'Host: staging.homesearch.world' \
    --header 'X-Forwarded-Proto: javascript' \
    "${base_url}/oauth2/authorization/kakao")"
if ! grep -Fq '"forwardedProto":"http"' <<<"${invalid_oauth_headers}"; then
    echo "상태: Fail - 허용되지 않은 forwarded protocol이 내부 scheme으로 fallback되지 않았습니다: ${invalid_oauth_headers}" >&2
    exit 1
fi

for path in \
    /api/v1/insights \
    /api/v1/insights/unknown \
    /api/v1/insights/subscription/ \
    /api/v1/admin \
    /api/v1/chatbot \
    /api/v1/chatbot/messages \
    /api/v1/chatbot/query/extra \
    /internal \
    /internal/health \
    /actuator/prometheus \
    /metrics \
    /metrics/process; do
    assert_route "$path" 404
done

docker stop --time 1 "$seo_upstream" >/dev/null
seo_failure_headers="$(mktemp)"
seo_failure_status="$(curl --silent --show-error --max-time 10 --dump-header "$seo_failure_headers" --output /dev/null --write-out '%{http_code}' "${base_url}/complexes/501")"
[[ "$seo_failure_status" == "503" ]] || { echo "상태: Fail - renderer 장애가 503으로 변환되지 않았습니다." >&2; exit 1; }
grep -Fiq 'Retry-After: 60' "$seo_failure_headers"
grep -Fiq 'X-Robots-Tag: noindex, nofollow' "$seo_failure_headers"
rm -f "$seo_failure_headers"

legal_failure_status="$(curl --silent --show-error --max-time 10 --output /dev/null --write-out '%{http_code}' "${base_url}/privacy")"
[[ "$legal_failure_status" == "503" ]] || { echo "상태: Fail - 법적 페이지 renderer 장애가 503으로 변환되지 않았습니다." >&2; exit 1; }

echo "상태: Pass - OAuth/API 경계와 SEO 200/404/503, robots, UA 동일성을 확인했습니다."
