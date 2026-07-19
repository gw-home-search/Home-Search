#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
runner="${script_dir}/run-local-chatbot.sh"
chatbot_compose="${repo_root}/infra/docker-compose.chatbot.yml"
tmp_dir="$(mktemp -d)"

cleanup() {
    find "$tmp_dir" -type f -exec unlink {} \; 2>/dev/null || true
    find "$tmp_dir" -depth -type d -exec rmdir {} \; 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "$tmp_dir/bin" "$tmp_dir/keys"
printf '%s\n' \
    '#!/usr/bin/env bash' \
    'if [[ "${1:-}" == "inspect" ]]; then printf "running|healthy\\n"; fi' \
    'if [[ "${HOME_AI_PROPERTY_DSN:-}" == *%40* ]]; then dsn_reserved_encoded=yes; else dsn_reserved_encoded=no; fi' \
    'printf "%s|home-search-set=%s|property-runtime=%s|bff-mapping=%s|ai-mapping=%s|dsn-reserved-encoded=%s|openai-key-set=%s|primary=%s|secondary=%s|timeout=%s|query-timeout=%s|capabilities=%s\\n" "$*" "${HOME_SEARCH_DB_PASSWORD:+yes}" "${PROPERTY_RUNTIME_DB_PASSWORD:-missing}" "${HOME_CHAT_BFF_JWT_PUBLIC_KEY_PATHS:-missing}" "${HOME_AI_JWT_PUBLIC_KEY_PATHS:-missing}" "$dsn_reserved_encoded" "${HOME_AI_OPENAI_API_KEY:+yes}" "${HOME_AI_OPENAI_PRIMARY_MODEL:-missing}" "${HOME_AI_OPENAI_SECONDARY_MODEL:-missing}" "${HOME_AI_OPENAI_TIMEOUT_SECONDS:-missing}" "${HOME_AI_QUERY_TIMEOUT_SECONDS:-missing}" "${HOME_AI_ENABLED_PROPERTY_CAPABILITIES:-missing}" >>"$CHATBOT_TEST_DOCKER_LOG"' \
    'printf "reference-dsn-set=%s|reference-capabilities=%s\\n" "${HOME_AI_REFERENCE_DSN:+yes}" "${HOME_AI_ENABLED_REFERENCE_CAPABILITIES:-}" >>"$CHATBOT_TEST_DOCKER_LOG"' \
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

append_ai_data_passwords() {
    printf '%s\n' \
        'AI_DATA_MIGRATOR_DB_PASSWORD=ai-data-migrator-secret' \
        'AI_DATA_IMPORTER_DB_PASSWORD=ai-data-importer-secret' \
        'AI_DATA_RUNTIME_DB_PASSWORD=ai-data-runtime-secret' >>"$property_env"
}

printf '%s\n' \
    'HOME_SEARCH_DB_PASSWORD=cluster-secret' \
    'PROPERTY_RUNTIME_DB_PASSWORD=property-runtime-secret' \
    'PROPERTY_MIGRATOR_DB_PASSWORD=property-migrator-secret' \
    'AI_PROPERTY_READER_DB_PASSWORD=ai-reader-secret' \
    'USER_RUNTIME_DB_PASSWORD=user-runtime-secret' \
    'USER_MIGRATOR_DB_PASSWORD=user-migrator-secret' >"$property_env"
append_ai_data_passwords
printf '%s\n' \
    'USER_JWT_ACTIVE_KID=local-user-1' \
    'USER_DB_PASSWORD=user-runtime-secret' >"$user_env"
printf '%s\n' \
    'HOME_CHAT_BFF_JWT_PUBLIC_KEY_PATHS=local-user-1=/run/keys/user-signing-public' >"$bff_env"
printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai-reader-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_API_KEY=openai-test-secret' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-primary-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-secondary-test' \
    'HOME_AI_OPENAI_TIMEOUT_SECONDS=7' \
    'HOME_AI_QUERY_TIMEOUT_SECONDS=40' \
    'HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity' \
    'HOME_AI_REFERENCE_DSN=postgresql://home_search_ai_runtime:ai-data-runtime-secret@postgis:5432/home_search_ai' \
    'HOME_AI_ENABLED_REFERENCE_CAPABILITIES=' >"$ai_env"

if [[ ! -x "$runner" ]]; then
    echo "상태: Fail - local chatbot runner가 없습니다." >&2
    exit 1
fi
bff_compose_section="$(sed -n '/^  chat-bff:/,/^  public-api-gateway:/p' "$chatbot_compose")"
grep -Fq -- '- CMD' <<<"$bff_compose_section"
grep -Fq -- '- bash' <<<"$bff_compose_section"
grep -Fq -- '- -ec' <<<"$bff_compose_section"
grep -Fq 'HOME_CHAT_BFF_AI_TIMEOUT: ${HOME_CHAT_BFF_AI_TIMEOUT:-70s}' <<<"$bff_compose_section"
grep -Fq 'HOME_AI_QUERY_TIMEOUT_SECONDS: ${HOME_AI_QUERY_TIMEOUT_SECONDS:-45}' "$chatbot_compose"
grep -Fq 'HOME_AI_REFERENCE_DSN: ${HOME_AI_REFERENCE_DSN:?Set HOME_AI_REFERENCE_DSN}' "$chatbot_compose"
grep -Fq 'HOME_AI_ENABLED_REFERENCE_CAPABILITIES: ${HOME_AI_ENABLED_REFERENCE_CAPABILITIES:-}' "$chatbot_compose"

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
if grep -Eq 'cluster-secret|migrator-secret|reader-secret|runtime-secret|openai-test-secret' <<<"$output"; then
    echo "상태: Fail - runner 출력에 비밀값이 포함됐습니다." >&2
    exit 1
fi
grep -Fq 'compose -f' "$docker_log"
grep -Fq 'config --quiet' "$docker_log"
grep -Fq 'property-runtime=property-runtime-secret' "$docker_log"
grep -Fq 'openai-key-set=yes|primary=gpt-5-primary-test|secondary=gpt-5-secondary-test|timeout=7|query-timeout=40' "$docker_log"
grep -Fq 'capabilities=complex_identity' "$docker_log"
grep -Fq 'reference-dsn-set=yes|reference-capabilities=' "$docker_log"
grep -Fq 'exec --env AI_DATA_MIGRATOR_DB_PASSWORD --env AI_DATA_IMPORTER_DB_PASSWORD --env AI_DATA_RUNTIME_DB_PASSWORD --env AI_DATABASE_ONLY -i home-search-postgis bash -s' "$docker_log"
grep -Fq -- '--profile user' "$docker_log"
grep -Fq 'up -d --build --force-recreate --no-deps user-service ai chat-bff public-api-gateway' "$docker_log"

default_output="$(
    PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_PROPERTY_VARS_FILE="$property_env" \
    CHATBOT_USER_VARS_FILE="$user_env" \
    CHATBOT_BFF_VARS_FILE="$bff_env" \
    CHATBOT_AI_VARS_FILE="$ai_env" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner"
)"
grep -Fq '상태: Pass - chatbot local preflight' <<<"$default_output"
if grep -Eq 'cluster-secret|migrator-secret|reader-secret|runtime-secret|openai-test-secret' \
    <<<"$default_output"; then
    echo "상태: Fail - 무인자 runner 출력에 비밀값이 포함됐습니다." >&2
    exit 1
fi

printf '%s\n' \
    'HOME_CHAT_BFF_JWT_PUBLIC_KEY_PATHS=wrong-kid=/run/keys/user-signing-public' >"$bff_env"
printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai-reader-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"wrong-kid":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_API_KEY=openai-test-secret' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-primary-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-secondary-test' \
    'HOME_AI_OPENAI_TIMEOUT_SECONDS=7' >"$ai_env"
PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_PROPERTY_VARS_FILE="$property_env" \
    CHATBOT_USER_VARS_FILE="$user_env" \
    CHATBOT_BFF_VARS_FILE="$bff_env" \
    CHATBOT_AI_VARS_FILE="$ai_env" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" >/dev/null
grep -Fq 'bff-mapping=local-user-1=/run/keys/user-signing-public' "$docker_log"
grep -Fq 'ai-mapping={"local-user-1":"/run/keys/user-signing-public"}' "$docker_log"
printf '%s\n' \
    'HOME_CHAT_BFF_JWT_PUBLIC_KEY_PATHS=local-user-1=/run/keys/user-signing-public' >"$bff_env"

printf '%s\n' \
    'HOME_SEARCH_DB_PASSWORD=cluster-secret' \
    'PROPERTY_RUNTIME_DB_PASSWORD=property-runtime-secret' \
    'PROPERTY_MIGRATOR_DB_PASSWORD=property-migrator-secret' \
    'AI_PROPERTY_READER_DB_PASSWORD=ai@reader-secret' \
    'USER_RUNTIME_DB_PASSWORD=user-runtime-secret' \
    'USER_MIGRATOR_DB_PASSWORD=user-migrator-secret' >"$property_env"
append_ai_data_passwords
printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai@reader-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_API_KEY=openai-test-secret' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-primary-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-secondary-test' \
    'HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity' >"$ai_env"
PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_PROPERTY_VARS_FILE="$property_env" \
    CHATBOT_USER_VARS_FILE="$user_env" \
    CHATBOT_BFF_VARS_FILE="$bff_env" \
    CHATBOT_AI_VARS_FILE="$ai_env" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" >/dev/null
grep -Fq 'dsn-reserved-encoded=yes' "$docker_log"

if PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" \
    >"$tmp_dir/ai-dsn-not-encoded.out" 2>&1; then
    echo "상태: Fail - URL-encode되지 않은 AI reader DSN이 거부되지 않았습니다." >&2
    exit 1
fi
grep -Fq '거부됨: HOME_AI_PROPERTY_DSN과 AI_PROPERTY_READER_DB_PASSWORD가 일치하지 않습니다.' \
    "$tmp_dir/ai-dsn-not-encoded.out"

printf '%s\n' \
    'HOME_SEARCH_DB_PASSWORD=cluster-secret' \
    'PROPERTY_RUNTIME_DB_PASSWORD=property-runtime-secret' \
    'PROPERTY_MIGRATOR_DB_PASSWORD=property-migrator-secret' \
    'AI_PROPERTY_READER_DB_PASSWORD=ai-reader-secret' \
    'USER_RUNTIME_DB_PASSWORD=user-runtime-secret' \
    'USER_MIGRATOR_DB_PASSWORD=user-migrator-secret' >"$property_env"
append_ai_data_passwords

printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai-reader-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_API_KEY=openai-test-secret' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-primary-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-secondary-test' \
    'HOME_AI_OPENAI_TIMEOUT_SECONDS=7' >"$ai_env"
PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_PROPERTY_VARS_FILE="$property_env" \
    CHATBOT_USER_VARS_FILE="$user_env" \
    CHATBOT_BFF_VARS_FILE="$bff_env" \
    CHATBOT_AI_VARS_FILE="$ai_env" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" >/dev/null
grep -Fq 'capabilities=complex_identity' "$docker_log"

printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai-reader-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_API_KEY=openai-test-secret' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-primary-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-secondary-test' \
    'HOME_AI_OPENAI_TIMEOUT_SECONDS=7' \
    '  HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity' >"$ai_env"
PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" >/dev/null

printf '%s\n' \
    'DB_JDBC_URL=jdbc:postgresql://localhost:15432/home_search' \
    'DB_USERNAME=home_search' \
    'DB_PASSWORD=cluster-secret' \
    'PROPERTY_MIGRATOR_DB_PASSWORD=property-migrator-secret' \
    'AI_PROPERTY_READER_DB_PASSWORD=ai-reader-secret' >"$property_env"
append_ai_data_passwords
PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" >/dev/null
grep -Fq 'home-search-set=yes|property-runtime=property_runtime_local_password' "$docker_log"

printf '%s\n' \
    'DB_JDBC_URL=jdbc:postgresql://localhost:15432/home_search' \
    'DB_USERNAME=home_search_property_runtime' \
    'DB_PASSWORD=property-runtime-secret' \
    'PROPERTY_MIGRATOR_DB_PASSWORD=property-migrator-secret' \
    'AI_PROPERTY_READER_DB_PASSWORD=ai-reader-secret' \
    'USER_MIGRATOR_DB_PASSWORD=user-migrator-secret' >"$property_env"
append_ai_data_passwords
if PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" \
    >"$tmp_dir/bootstrap-alias-invalid.out" 2>&1; then
    echo "상태: Fail - runtime DB_PASSWORD의 bootstrap 재사용이 거부되지 않았습니다." >&2
    exit 1
fi
grep -Fq '거부됨: DB_PASSWORD를 bootstrap에 사용하려면 DB_USERNAME은 home_search여야 합니다.' \
    "$tmp_dir/bootstrap-alias-invalid.out"
if grep -Eq 'cluster-secret|migrator-secret|reader-secret|runtime-secret' \
    "$tmp_dir/bootstrap-alias-invalid.out"; then
    echo "상태: Fail - bootstrap alias 거부 출력에 비밀값이 포함됐습니다." >&2
    exit 1
fi

printf '%s\n' \
    'HOME_SEARCH_DB_PASSWORD=cluster-secret' \
    'PROPERTY_RUNTIME_DB_PASSWORD=property-runtime-secret' \
    'PROPERTY_MIGRATOR_DB_PASSWORD=property-migrator-secret' \
    'AI_PROPERTY_READER_DB_PASSWORD=ai-reader-secret' \
    'USER_RUNTIME_DB_PASSWORD=user-runtime-secret' \
    'USER_MIGRATOR_DB_PASSWORD=user-migrator-secret' >"$property_env"
append_ai_data_passwords

printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai-reader-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_API_KEY=openai-test-secret' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-primary-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-secondary-test' \
    'HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity' >"$ai_env"
PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" >/dev/null
grep -Fq 'openai-key-set=yes|primary=gpt-5-primary-test|secondary=gpt-5-secondary-test|timeout=8|query-timeout=45' "$docker_log"

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
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_API_KEY=openai-test-secret' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-primary-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-secondary-test' \
    'HOME_AI_OPENAI_TIMEOUT_SECONDS=7' \
    'HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity' >"$ai_env"
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

printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai-reader-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-primary-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-secondary-test' \
    'HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity' >"$ai_env"
if PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" \
    >"$tmp_dir/openai-key-missing.out" 2>&1; then
    echo "상태: Fail - OpenAI API key 누락이 거부되지 않았습니다." >&2
    exit 1
fi
grep -Fq '거부됨: HOME_AI_OPENAI_API_KEY는 정확히 한 번 정의해야 합니다.' \
    "$tmp_dir/openai-key-missing.out"

printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai-reader-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_API_KEY=openai-test-secret' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-primary-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-secondary-test' \
    'HOME_AI_OPENAI_TIMEOUT_SECONDS=31' \
    'HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity' >"$ai_env"
if PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" \
    >"$tmp_dir/openai-timeout-invalid.out" 2>&1; then
    echo "상태: Fail - OpenAI timeout 범위 오류가 거부되지 않았습니다." >&2
    exit 1
fi
grep -Fq '거부됨: HOME_AI_OPENAI 설정이 올바르지 않습니다.' \
    "$tmp_dir/openai-timeout-invalid.out"

printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai-reader-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_API_KEY=openai-test-secret' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-same-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-same-test' \
    'HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity' >"$ai_env"
if PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" \
    >"$tmp_dir/openai-models-invalid.out" 2>&1; then
    echo "상태: Fail - 동일한 OpenAI primary/secondary model이 거부되지 않았습니다." >&2
    exit 1
fi
grep -Fq '거부됨: HOME_AI_OPENAI 설정이 올바르지 않습니다.' \
    "$tmp_dir/openai-models-invalid.out"

printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai-reader-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_API_KEY=openai-test-secret' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-primary-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-secondary-test' >"$ai_env"
if PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" \
    >"$tmp_dir/capability-missing.out" 2>&1; then
    echo "상태: Fail - property Capability 누락이 거부되지 않았습니다." >&2
    exit 1
fi
grep -Fq '거부됨: HOME_AI_ENABLED_PROPERTY_CAPABILITIES는 정확히 한 번 정의해야 합니다.' \
    "$tmp_dir/capability-missing.out"

printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai-reader-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_API_KEY=openai-test-secret' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-primary-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-secondary-test' \
    'HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity,recent_trade_lookup' >"$ai_env"
if ! PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" \
    >"$tmp_dir/capability-approved.out" 2>&1; then
    echo "상태: Fail - 승인된 recent trade 누적 Capability가 허용되지 않았습니다." >&2
    exit 1
fi
grep -Fq '상태: Pass - chatbot local preflight' "$tmp_dir/capability-approved.out"

printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai-reader-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_API_KEY=openai-test-secret' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-primary-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-secondary-test' \
    'HOME_AI_QUERY_TIMEOUT_SECONDS=60' \
    'HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity,recent_trade_lookup,price_trend' >"$ai_env"
if ! PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" \
    >"$tmp_dir/capability-trend-approved.out" 2>&1; then
    echo "상태: Fail - 승인된 price trend 누적 Capability가 허용되지 않았습니다." >&2
    exit 1
fi
grep -Fq '상태: Pass - chatbot local preflight' "$tmp_dir/capability-trend-approved.out"

printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai-reader-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_API_KEY=openai-test-secret' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-primary-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-secondary-test' \
    'HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity,recent_trade_lookup,price_trend,price_trend' >"$ai_env"
if PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" \
    >"$tmp_dir/capability-invalid.out" 2>&1; then
    echo "상태: Fail - 중복 price trend Capability가 거부되지 않았습니다." >&2
    exit 1
fi
grep -Fq '거부됨: HOME_AI_ENABLED_PROPERTY_CAPABILITIES는 승인된 누적 설정만 허용합니다.' \
    "$tmp_dir/capability-invalid.out"

printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai-reader-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_API_KEY=openai-test-secret' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-primary-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-secondary-test' \
    'HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity,recent_trade_lookup,price_trend' \
    'HOME_AI_ENABLED_REFERENCE_CAPABILITIES=school_location' >"$ai_env"
if ! PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" \
    >"$tmp_dir/reference-capability-approved.out" 2>&1; then
    echo "상태: Fail - 승인된 school_location Capability가 허용되지 않았습니다." >&2
    exit 1
fi
grep -Fq 'reference-dsn-set=yes|reference-capabilities=school_location' "$docker_log"

printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai-reader-secret@postgis:5432/home_search' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_API_KEY=openai-test-secret' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-primary-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-secondary-test' \
    'HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity,recent_trade_lookup,price_trend' \
    'HOME_AI_ENABLED_REFERENCE_CAPABILITIES=school_location,school_location' >"$ai_env"
if PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" \
    >"$tmp_dir/reference-capability-invalid.out" 2>&1; then
    echo "상태: Fail - 중복 school_location Capability가 거부되지 않았습니다." >&2
    exit 1
fi
grep -Fq '거부됨: HOME_AI_ENABLED_REFERENCE_CAPABILITIES는 빈 값 또는 school_location만 허용합니다.' \
    "$tmp_dir/reference-capability-invalid.out"

printf '%s\n' \
    'HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:ai-reader-secret@postgis:5432/home_search' \
    'HOME_AI_REFERENCE_DSN=postgresql://home_search_ai_runtime:different-secret@postgis:5432/home_search_ai' \
    'HOME_AI_JWT_PUBLIC_KEY_PATHS={"local-user-1":"/run/keys/user-signing-public"}' \
    'HOME_AI_OPENAI_API_KEY=openai-test-secret' \
    'HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5-primary-test' \
    'HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5-secondary-test' \
    'HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity,recent_trade_lookup,price_trend' >"$ai_env"
if PATH="$tmp_dir/bin:$PATH" \
    CHATBOT_TEST_DOCKER_LOG="$docker_log" \
    CHATBOT_BFF_JAR_PATH="$tmp_dir/chat-bff.jar" \
    CHATBOT_AI_DOCKERFILE_PATH="$tmp_dir/Dockerfile" \
    CHATBOT_USER_PUBLIC_KEY_PATH="$tmp_dir/keys/public" \
    CHATBOT_USER_PRIVATE_KEY_PATH="$tmp_dir/keys/private" \
    "$runner" "$property_env" "$user_env" "$bff_env" "$ai_env" \
    >"$tmp_dir/reference-dsn-invalid.out" 2>&1; then
    echo "상태: Fail - reference runtime password 불일치가 거부되지 않았습니다." >&2
    exit 1
fi
grep -Fq '거부됨: HOME_AI_REFERENCE_DSN과 AI_DATA_RUNTIME_DB_PASSWORD가 일치하지 않습니다.' \
    "$tmp_dir/reference-dsn-invalid.out"

if grep -R -Eq 'openai-test-secret|ai-data-(migrator|importer|runtime)-secret' "$tmp_dir" \
    --exclude='ai.env' --exclude='property.env'; then
    echo "상태: Fail - runner artifact에 비밀값이 포함됐습니다." >&2
    exit 1
fi

echo "상태: Pass - chatbot local runner preflight와 비밀값 비노출을 확인했습니다."
