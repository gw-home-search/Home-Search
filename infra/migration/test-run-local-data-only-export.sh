#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
runner="${script_dir}/run-local-data-only-export.sh"
test_dir="$(mktemp -d)"
fake_bin="${test_dir}/bin"
property_vars="${test_dir}/property.env"
ai_vars="${test_dir}/ai.env"
output_dir="${test_dir}/evidence/property-reference-20260729"
runner_log="${test_dir}/runner.log"
docker_log="${test_dir}/docker-arguments.json"
env_path_log="${test_dir}/docker-env-path.txt"
image_uri="123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/backup@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
property_secret='property-export-secret'
access_key='local-minio-access-key'
secret_key='local-minio-secret-key'
session_token='local-minio-session-token'

cleanup() {
  find "${test_dir}" -depth -delete 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "${fake_bin}"
cat >"${property_vars}" <<EOF
HOME_SEARCH_DB_PASSWORD=${property_secret}
EOF
cat >"${ai_vars}" <<EOF
AWS_ACCESS_KEY_ID=${access_key}
AWS_SECRET_ACCESS_KEY=${secret_key}
AWS_SESSION_TOKEN=${session_token}
HOME_AI_RAW_S3_BUCKET=home-search-reference-raw
HOME_AI_RAW_S3_REGION=ap-northeast-2
HOME_AI_RAW_S3_ENDPOINT=http://minio:9000
EOF
chmod 600 "${property_vars}" "${ai_vars}"

cat >"${fake_bin}/docker" <<'PY'
#!/usr/bin/env python3
import hashlib
import json
import os
import stat
import sys
from pathlib import Path


arguments = sys.argv[1:]
if arguments and arguments[0] == "inspect":
    print("home-search-local_home-search-local")
    raise SystemExit(0)
if not arguments or arguments[0] != "run":
    raise SystemExit(2)

env_file = Path(arguments[arguments.index("--env-file") + 1])
if stat.S_IMODE(env_file.stat().st_mode) != 0o600:
    raise SystemExit("temporary env file mode is not 0600")
values = {}
for line in env_file.read_text(encoding="utf-8").splitlines():
    key, value = line.split("=", 1)
    values[key] = value
expected = {
    "HOME_MIGRATION_PROPERTY_SOURCE_PASSWORD": os.environ["TEST_PROPERTY_SECRET"],
    "HOME_MIGRATION_REFERENCE_SOURCE_PASSWORD": os.environ["TEST_PROPERTY_SECRET"],
    "AWS_ACCESS_KEY_ID": os.environ["TEST_ACCESS_KEY"],
    "AWS_SECRET_ACCESS_KEY": os.environ["TEST_SECRET_KEY"],
    "AWS_SESSION_TOKEN": os.environ["TEST_SESSION_TOKEN"],
    "HOME_MIGRATION_RAW_SOURCE_BUCKET": "home-search-reference-raw",
    "HOME_MIGRATION_RAW_SOURCE_REGION": "ap-northeast-2",
    "HOME_MIGRATION_RAW_SOURCE_ENDPOINT": "http://minio:9000",
}
if any(values.get(key) != value for key, value in expected.items()):
    raise SystemExit("mapped export environment is incomplete")
if values.get("HOME_MIGRATION_PROPERTY_SOURCE_HOST") != "postgis":
    raise SystemExit("property source host is not the Docker service alias")
if values.get("HOME_MIGRATION_REFERENCE_SOURCE_DATABASE") != "home_search_ai":
    raise SystemExit("reference source database is not isolated")

image_uri = os.environ["TEST_IMAGE_URI"]
image_index = arguments.index(image_uri)
if arguments[image_index + 1 :] != ["--data-export", "/evidence"]:
    raise SystemExit("backup image entrypoint arguments are not exact")
if "--read-only" not in arguments:
    raise SystemExit("container root filesystem is writable")
if "--pull=never" not in arguments:
    raise SystemExit("container can implicitly pull an unverified image")
if arguments[arguments.index("--cap-drop") + 1] != "ALL":
    raise SystemExit("container capabilities were not dropped")
if arguments[arguments.index("--security-opt") + 1] != "no-new-privileges":
    raise SystemExit("container can gain privileges")
if arguments[arguments.index("--user") + 1] == "0:0":
    raise SystemExit("container runs as root")
if arguments[arguments.index("--tmpfs") + 1] != "/tmp:rw,noexec,nosuid,nodev,size=256m":
    raise SystemExit("temporary filesystem is not bounded")
volume = arguments[arguments.index("--volume") + 1]
host_path, container_path, access = volume.rsplit(":", 2)
if container_path != "/evidence" or access != "rw":
    raise SystemExit("evidence mount is not exact")
output = Path(host_path)
Path(os.environ["TEST_ENV_PATH_LOG"]).write_text(str(env_file), encoding="utf-8")
if os.environ.get("TEST_DOCKER_FAIL") == "1":
    raise SystemExit(1)

catalog = json.loads(Path(os.environ["TEST_CATALOG"]).read_text(encoding="utf-8"))
canonical = (json.dumps(catalog, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()
manifest = {
    "formatVersion": 1,
    "migrationId": "20260729T010203Z-0123456789abcdef",
    "createdAt": "2026-07-29T01:02:03+00:00",
    "catalogSha256": hashlib.sha256(canonical).hexdigest(),
    "sourceWatermarks": {},
    "datasets": [
        {
            "dataset": f"{item['logicalDatabase']}:{item['schema']}.{item['table']}",
            "rowCount": 0,
        }
        for item in catalog["datasets"]
    ],
    "chunks": [],
    "rawObjects": [],
}
(output / "data-only-manifest.json").write_text(
    json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
    encoding="utf-8",
)
Path(os.environ["TEST_DOCKER_LOG"]).write_text(json.dumps(arguments), encoding="utf-8")
PY
chmod +x "${fake_bin}/docker"

TEST_PROPERTY_SECRET="${property_secret}" \
TEST_ACCESS_KEY="${access_key}" \
TEST_SECRET_KEY="${secret_key}" \
TEST_SESSION_TOKEN="${session_token}" \
TEST_IMAGE_URI="${image_uri}" \
TEST_CATALOG="${script_dir}/data-only-allowlist.json" \
TEST_DOCKER_LOG="${docker_log}" \
TEST_ENV_PATH_LOG="${env_path_log}" \
PATH="${fake_bin}:${PATH}" \
  "${runner}" \
    --image-uri "${image_uri}" \
    --output "${output_dir}" \
    --property-vars-file "${property_vars}" \
    --ai-vars-file "${ai_vars}" >"${runner_log}" 2>&1

grep -Fx '상태: Pass' "${runner_log}" >/dev/null
grep -Fx "manifest_path=${output_dir}/data-only-manifest.json" "${runner_log}" >/dev/null
grep -E '^manifest_sha256=[0-9a-f]{64}$' "${runner_log}" >/dev/null
grep -Fx 'property_dataset_count=46' "${runner_log}" >/dev/null
grep -Fx 'reference_dataset_count=21' "${runner_log}" >/dev/null
[[ "$(stat -c '%a' "${output_dir}" 2>/dev/null || stat -f '%Lp' "${output_dir}")" == '700' ]]
[[ "$(stat -c '%a' "${output_dir}/data-only-manifest.json" 2>/dev/null || stat -f '%Lp' "${output_dir}/data-only-manifest.json")" == '600' ]]

temporary_env_path="$(cat "${env_path_log}")"
[[ ! -e "${temporary_env_path}" ]]
for secret in "${property_secret}" "${access_key}" "${secret_key}" "${session_token}"; do
  ! grep -F -- "${secret}" "${runner_log}" >/dev/null
  ! grep -F -- "${secret}" "${docker_log}" >/dev/null
done

set +e
PATH="${fake_bin}:${PATH}" "${runner}" \
  --image-uri '123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/backup:1.0.6' \
  --output "${test_dir}/mutable-tag" \
  --property-vars-file "${property_vars}" \
  --ai-vars-file "${ai_vars}" >"${test_dir}/mutable.log" 2>&1
mutable_status=$?
set -e
[[ "${mutable_status}" == '2' ]]
grep -F 'digest로 고정' "${test_dir}/mutable.log" >/dev/null

TEST_PROPERTY_SECRET="${property_secret}" \
TEST_ACCESS_KEY="${access_key}" \
TEST_SECRET_KEY="${secret_key}" \
TEST_SESSION_TOKEN="${session_token}" \
TEST_IMAGE_URI="${image_uri}" \
TEST_CATALOG="${script_dir}/data-only-allowlist.json" \
TEST_DOCKER_LOG="${test_dir}/failed-docker-arguments.json" \
TEST_ENV_PATH_LOG="${test_dir}/failed-docker-env-path.txt" \
TEST_DOCKER_FAIL=1 \
PATH="${fake_bin}:${PATH}" \
  "${runner}" \
    --image-uri "${image_uri}" \
    --output "${test_dir}/failed-export" \
    --property-vars-file "${property_vars}" \
    --ai-vars-file "${ai_vars}" >"${test_dir}/failed-export.log" 2>&1 && exit 1
failed_env_path="$(cat "${test_dir}/failed-docker-env-path.txt")"
[[ ! -e "${failed_env_path}" ]]
grep -F 'partial evidence는 보존' "${test_dir}/failed-export.log" >/dev/null

chmod 644 "${property_vars}"
set +e
PATH="${fake_bin}:${PATH}" "${runner}" \
  --image-uri "${image_uri}" \
  --output "${test_dir}/insecure-vars" \
  --property-vars-file "${property_vars}" \
  --ai-vars-file "${ai_vars}" >"${test_dir}/permissions.log" 2>&1
permissions_status=$?
set -e
[[ "${permissions_status}" == '2' ]]
grep -F 'chmod 600' "${test_dir}/permissions.log" >/dev/null

echo '상태: Pass - local data-only export runner contract'
