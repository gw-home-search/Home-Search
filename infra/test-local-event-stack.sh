#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="${repo_root}/infra/docker-compose.local.yml"

python3 - "${compose_file}" <<'PY'
from pathlib import Path
import sys

content = Path(sys.argv[1]).read_text(encoding="utf-8")
required = (
    "  redpanda:\n",
    "profiles: [ \"events\" ]",
    "--advertise-kafka-addr=internal://redpanda:9092,external://localhost:19092",
    "127.0.0.1:${HOME_SEARCH_KAFKA_PORT:-19092}:19092",
    "  event-topics:\n",
    "property.insight-events.v1.dlq",
    'rpk topic create "$$topic" --partitions "$$partitions" --replicas 1',
    "ensure_topic property.insight-events.v1.dlq 1 2592000000",
    "  property-event-relay:\n",
    "SPRING_BATCH_JOB_NAME: propertyEventRelayJob",
    "HOME_KAFKA_BOOTSTRAP_SERVERS: redpanda:9092",
    "  user-insight-worker:\n",
    "SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL: PLAINTEXT",
    "condition: service_completed_successfully",
)
missing = [fragment for fragment in required if fragment not in content]
if missing:
    raise SystemExit("상태: Fail - local event stack 계약이 없습니다: " + ", ".join(repr(item) for item in missing))
PY

echo "상태: Pass - opt-in local Kafka topic, relay, worker 구성을 확인했습니다."
