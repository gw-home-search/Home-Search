#!/usr/bin/env bash
set -Eeuo pipefail

suffix="${RANDOM}-$$"
network="home-search-edge-test-${suffix}"
public_image="home-search-public-gateway:test-${suffix}"
admin_image="home-search-admin-gateway:test-${suffix}"
property_upstream="home-search-property-upstream-${suffix}"
user_upstream="home-search-user-upstream-${suffix}"
admin_upstream="home-search-admin-upstream-${suffix}"
chat_bff_upstream="home-search-chat-bff-upstream-${suffix}"
public_gateway="home-search-public-gateway-${suffix}"
admin_gateway="home-search-admin-gateway-${suffix}"
tmp_dir="$(mktemp -d)"

cleanup() {
  docker stop --time 1 "${public_gateway}" "${admin_gateway}" \
    "${property_upstream}" "${user_upstream}" "${admin_upstream}" \
    "${chat_bff_upstream}" >/dev/null 2>&1 || true
  docker network remove "${network}" >/dev/null 2>&1 || true
  find "${tmp_dir}" -depth -delete 2>/dev/null || true
}
trap cleanup EXIT

docker build --tag "${public_image}" --file apps/web/Dockerfile \
  --build-arg VITE_USER_API_SERVER_IP=http://localhost:8080 \
  --build-arg VITE_KAKAO_MAP_APP_KEY=test-public-key .
docker build --tag "${admin_image}" --file apps/admin/web/Dockerfile .

for image in "${public_image}" "${admin_image}"; do
  user="$(docker inspect --format '{{.Config.User}}' "${image}")"
  [[ -n "${user}" && "${user}" != '0' && "${user}" != 'root' ]]
  [[ "$(docker inspect --format '{{json .Config.Healthcheck.Test}}' "${image}")" != 'null' ]]
done

docker run --rm --entrypoint sh "${public_image}" -c \
  'test -f /usr/share/nginx/html/index.html && ! grep -Rqi "home-search-admin-web" /usr/share/nginx/html'
docker run --rm --entrypoint sh "${admin_image}" -c \
  'test -f /usr/share/nginx/html/index.html && test ! -e /usr/share/nginx/html/home-search-logo.png'

cat >"${tmp_dir}/property.conf" <<'EOF'
server { listen 8080; location / { default_type text/plain; return 200 "property:$request_uri"; } }
EOF
cat >"${tmp_dir}/user.conf" <<'EOF'
server { listen 8080; location / { default_type text/plain; return 200 "user:$request_uri"; } }
EOF
cat >"${tmp_dir}/admin.conf" <<'EOF'
server { listen 8080; location / { default_type text/plain; return 200 "admin:$request_uri"; } }
EOF
cat >"${tmp_dir}/chat-bff.conf" <<'EOF'
server { listen 8080; location / { default_type text/plain; return 200 "chat-bff:$request_uri"; } }
EOF

docker network create "${network}" >/dev/null
docker run --rm --detach --name "${property_upstream}" --network "${network}" \
  --network-alias property-api --volume "${tmp_dir}/property.conf:/etc/nginx/conf.d/default.conf:ro" nginx:1.27-alpine >/dev/null
docker run --rm --detach --name "${user_upstream}" --network "${network}" \
  --network-alias user-api --volume "${tmp_dir}/user.conf:/etc/nginx/conf.d/default.conf:ro" nginx:1.27-alpine >/dev/null
docker run --rm --detach --name "${admin_upstream}" --network "${network}" \
  --network-alias admin-api --volume "${tmp_dir}/admin.conf:/etc/nginx/conf.d/default.conf:ro" nginx:1.27-alpine >/dev/null
docker run --rm --detach --name "${chat_bff_upstream}" --network "${network}" \
  --network-alias chat-bff --volume "${tmp_dir}/chat-bff.conf:/etc/nginx/conf.d/default.conf:ro" nginx:1.27-alpine >/dev/null
docker run --rm --detach --name "${public_gateway}" --network "${network}" \
  --publish 127.0.0.1::8080 --env USER_API_PORT=8080 --env CHAT_BFF_PORT=8080 "${public_image}" >/dev/null
docker run --rm --detach --name "${admin_gateway}" --network "${network}" \
  --publish 127.0.0.1::8080 --env ADMIN_API_PORT=8080 "${admin_image}" >/dev/null

public_port="$(docker inspect --format='{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}' "${public_gateway}")"
admin_port="$(docker inspect --format='{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}' "${admin_gateway}")"

wait_for_gateway() {
  local port="$1"
  for _ in $(seq 1 30); do
    if curl --silent --fail --max-time 2 "http://127.0.0.1:${port}/" >/dev/null; then return 0; fi
    sleep 0.2
  done
  return 1
}
wait_for_gateway "${public_port}"
wait_for_gateway "${admin_port}"

assert_body() {
  local endpoint="$1"
  local expected="$2"
  local actual
  actual="$(curl --silent --fail "${endpoint}")"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "상태: Fail - ${endpoint} upstream 불일치: ${actual}" >&2
    exit 1
  fi
}
assert_body "http://127.0.0.1:${public_port}/api/v1/map/regions" 'property:/api/v1/map/regions'
assert_body "http://127.0.0.1:${public_port}/api/v1/users/me" 'user:/api/v1/users/me'
assert_body "http://127.0.0.1:${public_port}/auth/access" 'user:/auth/access'
assert_body "http://127.0.0.1:${public_port}/api/v1/chatbot/query" 'chat-bff:/api/v1/chatbot/query'
assert_body "http://127.0.0.1:${public_port}/api/v1/chatbot/query/stream" 'chat-bff:/api/v1/chatbot/query/stream'
assert_body "http://127.0.0.1:${admin_port}/api/v1/admin/auth/me" 'admin:/api/v1/admin/auth/me'

for endpoint in \
  "http://127.0.0.1:${public_port}/api/v1/admin/accounts" \
  "http://127.0.0.1:${public_port}/actuator/health" \
  "http://127.0.0.1:${public_port}/api/v1/chatbot/unknown" \
  "http://127.0.0.1:${admin_port}/api/v1/map/regions" \
  "http://127.0.0.1:${admin_port}/actuator/prometheus"; do
  [[ "$(curl --silent --output /dev/null --write-out '%{http_code}' "${endpoint}")" == '404' ]]
done

curl --silent --fail --dump-header "${tmp_dir}/spa.headers" \
  "http://127.0.0.1:${public_port}/complex/501" | grep -q '<div id="root"></div>'
grep -Eqi '^Cache-Control: no-cache' "${tmp_dir}/spa.headers"

asset_path="$(docker exec "${public_gateway}" sh -c \
  'find /usr/share/nginx/html/assets -type f | head -n 1 | sed "s#^/usr/share/nginx/html##"')"
curl --silent --fail --dump-header "${tmp_dir}/asset.headers" --output /dev/null \
  "http://127.0.0.1:${public_port}${asset_path}"
grep -Eqi '^Cache-Control: public, max-age=31536000, immutable' "${tmp_dir}/asset.headers"

echo '상태: Pass - public/admin SPA, API proxy, cache header, actuator 및 route 격리를 확인했습니다.'
