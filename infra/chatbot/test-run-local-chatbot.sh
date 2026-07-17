#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
runner="${script_dir}/run-local-chatbot.sh"
tmp_dir="$(mktemp -d)"

cleanup() {
    find "$tmp_dir" -type f -exec unlink {} \; 2>/dev/null || true
    find "$tmp_dir" -depth -type d -exec rmdir {} \; 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "$tmp_dir/bin" "$tmp_dir/keys"
printf '%s\n' \
    '#!/usr/bin/env bash' \
    'printf "%s|property-runtime=%s\\n" "$*" "${PROPERTY_RUNTIME_DB_PASSWORD:-missing}" >>"$CHATBOT_TEST_DOCKER_LOG"' \
    >"$tmp_dir/bin/docker"
chmod +x "$tmp_dir/bin/docker"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out "$tmp_dir/keys/private" >/dev/null 2>&1
openssl pkey -in "$tmp_dir/keys/private" -pubout \
    -out "$tmp_dir/keys/public" >/dev/null 2>&1
printf '%s\n' 'jar-fixture' >"$tmp_dir/chat-bff.jar"
printf '%s\n' 'dockerfile-fixture' >"$tmp_dir/Dockerfile"

property_env="$tmp_dir/property.env"
user_env="$tmp_dir/user.env"
bff_env="$tmp_dir/bff.env"
ai_env="$tmp_dir/ai.env"
docker_log="$tmp_dir/docker.log"

printf '%s\n' \
    'HOME_SEARCH_DB_PASSWORD=cluster-secret' \
    'PROPERTY_RUNTIME_DB_PASSWORD=property-runtime-secret' \
    'PROPERTY_MIGRATOR_DB_PASSWORD=property-migrator-secret' \
    'AI_PROPERTY_READER_DB_PASSWORD=ai-reader-secret' \
    'USER_RUNTIME_DB_PASSWORD=user-runtime-secret' \
    'USER_MIGRATOR_DB_PASSWORD=user-migrator-secret' >"$property_env"
printf '%s\n' \
    'USER_JWT_ACTIVE_KID=local-user-1' \
    'USER_DB_PASSWORD=user-runtime-secret' >"$user_env"
printf '%s\n' \
    'HOME_CHAT_BFF_JWT_PUBLIC_KEY_PATHS=local-user-1=/run/keys/user-signing-public' >"$bff_env"
printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai-reader-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' >"$ai_env"

if [[ ! -x "$runner" ]]; then
    echo "상태: Fail - local chatbot runner가 없습니다." >&2
    exit 1
fi

output="$(
    PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env"
)"

grep -Fq '상태: Pass - chatbot local preflight' <<<"$output"
if grep -Eq 'cluster-secret|migrator-secret|reader-secret|runtime-secret' <<<"$output"; then
    echo "상태: Fail - runner 출력에 비밀값이 포함됐습니다." >&2
    exit 1
fi
grep -Fq 'compose -f' "$docker_log"
grep -Fq 'config --quiet' "$docker_log"
grep -Fq 'property-runtime=property-runtime-secret' "$docker_log"
grep -Fq -- '--profile user' "$docker_log"
grep -Fq 'up -d --build user-service ai chat-bff public-api-gateway' "$docker_log"

printf '%s\n' \
    'HOME_CHAT_BFF_JWT_PUBLIC_KEY_PATHS=wrong-kid=/run/keys/user-signing-public' >"$bff_env"
if PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" \
    >"$tmp_dir/mismatch.out" 2>&1; then
    echo "상태: Fail - kid 불일치가 거부되지 않았습니다." >&2
    exit 1
fi
grep -Fq '거부됨: user JWT kid와 BFF public-key mapping이 일치하지 않습니다.' "$tmp_dir/mismatch.out"
if grep -Eq 'cluster-secret|migrator-secret|reader-secret|runtime-secret' "$tmp_dir/mismatch.out"; then
    echo "상태: Fail - 거부 출력에 비밀값이 포함됐습니다." >&2
    exit 1
fi

printf '%s\n' \
    'HOME_CHAT_BFF_JWT_PUBLIC_KEY_PATHS=local-user-1=/run/keys/user-signing-public' >"$bff_env"
printf '%s\n' \
    'USER_JWT_ACTIVE_KID=local-user-1' \
    'USER_DB_PASSWORD=different-secret' >"$user_env"
if PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" \
    >"$tmp_dir/user-password-mismatch.out" 2>&1; then
    echo "상태: Fail - user runtime password 불일치가 거부되지 않았습니다." >&2
    exit 1
fi
grep -Fq '거부됨: USER_DB_PASSWORD와 USER_RUNTIME_DB_PASSWORD가 일치하지 않습니다.' \
    "$tmp_dir/user-password-mismatch.out"

printf '%s\n' \
    'USER_JWT_ACTIVE_KID=local-user-1' \
    'USER_DB_PASSWORD=user-runtime-secret' >"$user_env"
printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:different-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' >"$ai_env"
if PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" \
    >"$tmp_dir/ai-password-mismatch.out" 2>&1; then
    echo "상태: Fail - AI reader password 불일치가 거부되지 않았습니다." >&2
    exit 1
fi
grep -Fq '거부됨: HOME_AI_PROPERTY_DSN과 AI_PROPERTY_READER_DB_PASSWORD가 일치하지 않습니다.' \
    "$tmp_dir/ai-password-mismatch.out"

echo "상태: Pass - chatbot local runner preflight와 비밀값 비노출을 확인했습니다."
