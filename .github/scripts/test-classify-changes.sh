#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
classifier="$root/.github/scripts/classify-changes.sh"

assert_case() {
  local name="$1"
  local expected="$2"
  shift 2
  local actual
  actual="$($classifier "$@" | sort)"
  if [[ "$actual" != "$expected" ]]; then
    printf '상태: Fail - %s\nexpected:\n%s\nactual:\n%s\n' "$name" "$expected" "$actual" >&2
    exit 1
  fi
}

none=$'admin_service_changed=false\nadmin_web_changed=false\nai_changed=false\nbackend_changed=false\nchat_bff_changed=false\nfrontend_changed=false\ninfra_changed=false\nml_changed=false\nsource_data_changed=false\nuser_service_changed=false'
all=$'admin_service_changed=true\nadmin_web_changed=true\nai_changed=true\nbackend_changed=true\nchat_bff_changed=true\nfrontend_changed=true\ninfra_changed=true\nml_changed=true\nsource_data_changed=true\nuser_service_changed=true'

assert_case "property" "${none/backend_changed=false/backend_changed=true}" \
  apps/property-data/core/src/main/java/com/home/Example.java
assert_case "source-data" "${none/source_data_changed=false/source_data_changed=true}" \
  apps/source-data/src/main/java/com/home/Example.java
assert_case "shared ingest library" "$(printf '%s' "$none" | sed 's/backend_changed=false/backend_changed=true/;s/source_data_changed=false/source_data_changed=true/')" \
  libs/rtms-ingest-core/src/main/java/com/home/Example.java
assert_case "frontend" "${none/frontend_changed=false/frontend_changed=true}" \
  apps/web/src/App.tsx
assert_case "AI service" "${none/ai_changed=false/ai_changed=true}" \
  apps/ai/ai_service/main.py
assert_case "chat BFF" "${none/chat_bff_changed=false/chat_bff_changed=true}" \
  apps/chat-bff/src/main/java/com/home/chatbff/ChatBffApplication.java
assert_case "chatbot public contract" "$(printf '%s' "$none" | sed 's/ai_changed=false/ai_changed=true/;s/chat_bff_changed=false/chat_bff_changed=true/')" \
  docs/CHATBOT_API_CONTRACT.md
assert_case "chatbot service plan" "$(printf '%s' "$none" | sed 's/ai_changed=false/ai_changed=true/;s/chat_bff_changed=false/chat_bff_changed=true/')" \
  docs/AI_SERVICE_PLAN.md
assert_case "shared user auth contract" "$(printf '%s' "$none" | sed 's/chat_bff_changed=false/chat_bff_changed=true/;s/user_service_changed=false/user_service_changed=true/')" \
  libs/user-auth-contract/src/main/java/com/home/security/user/VerifiedUser.java
assert_case "shared frontend lint policy" "$(printf '%s' "$none" | sed 's/admin_web_changed=false/admin_web_changed=true/;s/frontend_changed=false/frontend_changed=true/')" \
  tools/eslint/react-flat-config.mjs
assert_case "docs only" "$none" docs/README.md
assert_case "shared Docker build context" "$all" .dockerignore
assert_case "manual dispatch" "$all" --manual

printf '상태: Pass - change classifier self-test\n'
