#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/capture-service-state.sh"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
mkdir "${tmp_dir}/bin"

cat >"${tmp_dir}/bin/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
set -Eeuo pipefail
case "$*" in
  *'ecs describe-clusters'*)
    printf '%s\n' '{"clusters":[{"status":"ACTIVE"}],"failures":[]}'
    ;;
  *'ecs list-services'*)
    printf '%s\n' '["property-api","admin-api","user-api","ai","chat-bff","public-gateway","admin-gateway","ml","budget-postgres","budget-valkey"]'
    ;;
  *'ecs describe-services'*)
    service=''
    previous=''
    for argument in "$@"; do
      [[ "${previous}" != '--services' ]] || service="${argument}"
      previous="${argument}"
    done
    jq -n --arg service "${service}" '{serviceName:$service,taskDefinition:("arn:task/"+$service+":7"),desiredCount:1,runningCount:1,pendingCount:0,deployments:[{status:"PRIMARY",rolloutState:"COMPLETED"}]}'
    ;;
  *'ecs describe-task-definition'*)
    task=''
    previous=''
    for argument in "$@"; do
      [[ "${previous}" != '--task-definition' ]] || task="${argument}"
      previous="${argument}"
    done
    name="${task#arn:task/}"; name="${name%:7}"
    jq -n --arg name "${name}" '{containerDefinitions:[{name:$name,image:("registry/"+$name+"@sha256:"+("a"*64))}]}'
    ;;
  *) exit 2 ;;
esac
FAKE_AWS
chmod +x "${tmp_dir}/bin/aws"

PATH="${tmp_dir}/bin:${PATH}" bash "${script}" fixture-cluster \
  "${tmp_dir}/applications.json" "${tmp_dir}/platform.json"

jq -e '
  (.services | keys | sort) == ["admin-api","admin-gateway","ai","chat-bff","ml","property-api","public-gateway","user-api"]
  and all(.services[];
    (.task_definition | test("^arn:task/"))
    and .desired_count == 1 and .running_count == 1 and .pending_count == 0
    and .deployment_state == "COMPLETED")
' "${tmp_dir}/applications.json" >/dev/null
jq -e '(.services | keys | sort) == ["budget-postgres","budget-valkey"]' \
  "${tmp_dir}/platform.json" >/dev/null

if jq -e '.services | has("budget-postgres") or has("budget-valkey")' \
  "${tmp_dir}/applications.json" >/dev/null; then
  echo '상태: Fail - application rollback evidence에 platform service가 포함됐습니다.' >&2
  exit 1
fi
echo '상태: Pass - application rollback과 platform read-only evidence를 분리했습니다.'
