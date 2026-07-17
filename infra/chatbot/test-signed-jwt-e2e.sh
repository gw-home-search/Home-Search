#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
suffix="$$"
network="home-search-chatbot-signed-e2e-${suffix}"
property_container="home-search-chatbot-signed-property-${suffix}"
redis_container="home-search-chatbot-signed-redis-${suffix}"
ai_container="home-search-chatbot-signed-ai-${suffix}"
bff_container="home-search-chatbot-signed-bff-${suffix}"
gateway_container="home-search-chatbot-signed-gateway-${suffix}"
tmp_dir="$(mktemp -d)"
ai_image="home-search-ai:signed-e2e"
nginx_image="nginx:1.27-alpine"
redis_image="redis:7.4-alpine"
bff_image="eclipse-temurin:21-jdk"
bff_jar="${repo_root}/apps/chat-bff/build/libs/chat-bff.jar"
python="${repo_root}/apps/ai/.venv/bin/python"

cleanup() {
    for container in \
        "$gateway_container" "$bff_container" "$ai_container" \
        "$redis_container" "$property_container"; do
        docker stop "$container" >/dev/null 2>&1 || true
    done
    docker network remove "$network" >/dev/null 2>&1 || true
    find "$tmp_dir" -type f -exec unlink {} \; 2>/dev/null || true
    find "$tmp_dir" -depth -type d -exec rmdir {} \; 2>/dev/null || true
}
trap cleanup EXIT

fail() {
    echo "상태: Fail - $1" >&2
    exit 1
}

[[ -f "$bff_jar" && -s "$bff_jar" ]] || fail "chat BFF artifact가 없습니다."
[[ -x "$python" ]] || fail "AI test environment가 없습니다. uv sync --frozen --group test를 먼저 실행하세요."

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out "$tmp_dir/private" >/dev/null 2>&1
openssl pkey -in "$tmp_dir/private" -pubout \
    -out "$tmp_dir/public" >/dev/null 2>&1
chmod 600 "$tmp_dir/private" "$tmp_dir/public"
chmod 644 "$tmp_dir/public"

issue_token() {
    local issuer="$1"
    "$python" - "$tmp_dir/private" "$issuer" <<'PY'
from datetime import UTC, datetime, timedelta
from pathlib import Path
import sys

import jwt

private_key = Path(sys.argv[1]).read_text(encoding="utf-8")
issuer = sys.argv[2]
now = datetime.now(UTC)
print(
    jwt.encode(
        {
            "iss": issuer,
            "aud": "home-search-user-api",
            "sub": "42",
            "jti": "signed-e2e-token",
            "iat": now,
            "exp": now + timedelta(minutes=5),
            "role": "USER",
        },
        private_key,
        algorithm="RS256",
        headers={"kid": "signed-e2e"},
    )
)
PY
}

write_curl_config() {
    local path="$1"
    local token="$2"
    umask 077
    printf 'header = "Authorization: Bearer %s"\n' "$token" >"$path"
    printf '%s\n' 'header = "Content-Type: application/json"' >>"$path"
}

valid_token="$(issue_token user-service)"
write_curl_config "$tmp_dir/valid-curl-config" "$valid_token"
unset valid_token
wrong_issuer_token="$(issue_token wrong-issuer)"
write_curl_config "$tmp_dir/wrong-issuer-curl-config" "$wrong_issuer_token"
unset wrong_issuer_token

cat >"$tmp_dir/e2e_app.py" <<'PY'
from ai_service.chat import get_chatbot_engine
from ai_service.main import app


class SignedE2EEngine:
    async def query(self, *, request, user, request_id):
        return {
            "success": False,
            "status": "failed",
            "answer": "signed JWT test path",
            "requestId": request_id,
            "citations": [],
            "dataAsOf": None,
            "limitations": ["test-only engine"],
            "evidenceSummary": {
                "status": "unavailable",
                "capabilities": [],
                "factCount": 0,
                "citationCount": 0,
            },
        }


app.dependency_overrides[get_chatbot_engine] = SignedE2EEngine
PY

docker build --quiet --tag "$ai_image" "${repo_root}/apps/ai" >/dev/null
docker network create "$network" >/dev/null
docker run --rm --detach --name "$property_container" \
    --network "$network" --network-alias api \
    --volume "${repo_root}/infra/nginx/property-public-test-upstream.conf:/etc/nginx/conf.d/default.conf:ro" \
    "$nginx_image" >/dev/null
docker run --rm --detach --name "$redis_container" \
    --network "$network" --network-alias redis "$redis_image" >/dev/null
docker run --rm --detach --name "$ai_container" \
    --network "$network" --network-alias ai \
    --publish 127.0.0.1::8000 \
    --env 'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:e2e-only@postgis:5432/home_search' \
    --env 'HOME_AI_JWT_PUBLIC_KEY_PATHS={"signed-e2e":"/run/keys/user-public"}' \
    --volume "$tmp_dir/public:/run/keys/user-public:ro" \
    --volume "$tmp_dir/e2e_app.py:/app/e2e_app.py:ro" \
    "$ai_image" uvicorn e2e_app:app --host 0.0.0.0 --port 8000 >/dev/null
docker run --rm --detach --name "$bff_container" \
    --user 10001:10001 \
    --network "$network" --network-alias chat-bff \
    --workdir /app \
    --env HOME_CHAT_BFF_AI_BASE_URL=http://ai:8000 \
    --env HOME_CHAT_BFF_JWT_PUBLIC_KEY_PATHS=signed-e2e=/run/keys/user-public \
    --env SPRING_DATA_REDIS_HOST=redis \
    --volume "$bff_jar:/app/chat-bff.jar:ro" \
    --volume "$tmp_dir/public:/run/keys/user-public:ro" \
    "$bff_image" java -jar chat-bff.jar >/dev/null
docker run --rm --detach --name "$gateway_container" \
    --network "$network" \
    --publish 127.0.0.1::8080 \
    --env FRONTEND_URL=http://localhost:5173 \
    --volume "${repo_root}/infra/nginx/property-chatbot-public.conf:/etc/nginx/templates/default.conf.template:ro" \
    "$nginx_image" >/dev/null

host_port="$(docker inspect --format='{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}' "$gateway_container")"
base_url="http://127.0.0.1:${host_port}"
ai_host_port="$(docker inspect --format='{{(index (index .NetworkSettings.Ports "8000/tcp") 0).HostPort}}' "$ai_container")"

ready=false
for _ in $(seq 1 80); do
    if docker exec "$ai_container" python -c \
        "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=1)" \
        >/dev/null 2>&1 \
        && docker exec "$bff_container" bash -lc \
        "exec 3<>/dev/tcp/127.0.0.1/8083 && printf 'GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3 && grep -q '\"status\":\"UP\"' <&3" \
        >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 0.25
done
if [[ "$ready" != true ]]; then
    docker logs "$ai_container" >&2 || true
    docker logs "$bff_container" >&2 || true
    fail "AI/BFF가 준비되지 않았습니다."
fi

request_id="a0ea2cf8-8f50-4679-9ce3-22a999c5d8d2"
ai_status="$(curl --silent --show-error --config "$tmp_dir/valid-curl-config" \
    --output "$tmp_dir/ai-body" --write-out '%{http_code}' --max-time 10 \
    --header "X-Request-Id: ${request_id}" \
    --data '{"question":"서명 JWT AI 인증 검증"}' \
    "http://127.0.0.1:${ai_host_port}/api/v1/chatbot/query")"
if [[ "$ai_status" != "200" ]]; then
    docker logs "$ai_container" >&2 || true
    fail "서명 JWT AI 직접 요청이 200이어야 합니다: ${ai_status}"
fi
ai_invalid_status="$(curl --silent --show-error --config "$tmp_dir/wrong-issuer-curl-config" \
    --output "$tmp_dir/ai-invalid-body" --write-out '%{http_code}' --max-time 10 \
    --data '{"question":"AI 잘못된 issuer"}' \
    "http://127.0.0.1:${ai_host_port}/api/v1/chatbot/query")"
[[ "$ai_invalid_status" == "401" ]] \
    || fail "AI의 잘못된 issuer JWT는 401이어야 합니다: ${ai_invalid_status}"
grep -Fq '"code":"AUTHENTICATION_REQUIRED"' "$tmp_dir/ai-invalid-body"

json_status="$(curl --silent --show-error --config "$tmp_dir/valid-curl-config" \
    --output "$tmp_dir/json-body" --write-out '%{http_code}' --max-time 10 \
    --header "X-Request-Id: ${request_id}" \
    --data '{"question":"서명 JWT 통합 검증"}' \
    "${base_url}/api/v1/chatbot/query")"

if [[ "$json_status" != "200" ]]; then
    docker logs "$bff_container" >&2 || true
    fail "서명 JWT JSON 요청이 200이어야 합니다: ${json_status}"
fi
grep -Fq '"requestId":"a0ea2cf8-8f50-4679-9ce3-22a999c5d8d2"' "$tmp_dir/json-body"
grep -Fq '"answer":"signed JWT test path"' "$tmp_dir/json-body"

stream_status="$(curl --silent --show-error --config "$tmp_dir/valid-curl-config" \
    --output "$tmp_dir/stream-body" --write-out '%{http_code}' --max-time 10 \
    --header "X-Request-Id: ${request_id}" \
    --data '{"question":"서명 JWT SSE 통합 검증"}' \
    "${base_url}/api/v1/chatbot/query/stream")"
[[ "$stream_status" == "200" ]] || fail "서명 JWT SSE 요청이 200이어야 합니다: ${stream_status}"
grep -Fq 'event:final' "$tmp_dir/stream-body"
grep -Fq 'signed JWT test path' "$tmp_dir/stream-body"
if grep -Fq 'event:error' "$tmp_dir/stream-body"; then
    fail "성공 SSE에 error event가 포함됐습니다."
fi

invalid_status="$(curl --silent --show-error --config "$tmp_dir/wrong-issuer-curl-config" \
    --output "$tmp_dir/invalid-body" --write-out '%{http_code}' --max-time 10 \
    --data '{"question":"잘못된 issuer"}' \
    "${base_url}/api/v1/chatbot/query")"
[[ "$invalid_status" == "401" ]] || fail "잘못된 issuer JWT는 401이어야 합니다: ${invalid_status}"
grep -Fq '"code":"AUTHENTICATION_REQUIRED"' "$tmp_dir/invalid-body"

property_status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
    --max-time 5 --header 'Content-Type: application/json' --data '{}' \
    "${base_url}/api/v1/map/regions")"
[[ "$property_status" == "200" ]] || fail "기존 property route는 200이어야 합니다: ${property_status}"

echo "상태: Pass - 실제 서명 JWT JSON/SSE, 잘못된 issuer 401, property 회귀를 확인했습니다."
