#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/terraform/budget-production/files/configure-edge.sh.tftpl"
automation="${root}/infra/terraform/budget-production/edge_automation.tf"

for required in \
  'listen 80 default_server;' \
  'listen 443 ssl default_server;' \
  'server_name $${PUBLIC_HOSTNAME};' \
  'client_max_body_size 1m;' \
  'proxy_pass http://127.0.0.1:18000;' \
  'proxy_buffering off;' \
  'proxy_request_buffering off;' \
  'proxy_read_timeout 75s;' \
  'listen 127.0.0.1:18090;' \
  'install -m 0400' \
  '-passin "file:' \
  'aws acm export-certificate'; do
  grep -Fq -- "${required}" "${script}" || {
    echo "상태: Fail - budget host edge 필수 설정이 없습니다: ${required}" >&2
    exit 1
  }
done

grep -Fq 'count            = local.public_enabled ? 1 : 0' "${automation}"
grep -Fq 'count     = local.public_enabled ? 1 : 0' "${automation}"
grep -Fq 'resources   = [aws_acm_certificate.public[0].arn]' "${automation}"
! grep -Fq 'certificateArn' "${automation}"
if grep -Eq 'set -x|PrivateKey.*echo|cat .*private-key' "${script}"; then
  echo '상태: Fail - certificate private key가 command log에 노출될 수 있습니다.' >&2
  exit 1
fi

echo '상태: Pass - budget host TLS, unknown Host, SSE, loopback health, key mode를 확인했습니다.'
