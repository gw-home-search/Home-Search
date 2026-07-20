# 생활 인프라 priority reference offline 검증

검증일: 2026-07-20

상태: `Pass`

구현 품질: `9.5/10`

실제 provider 호출, 운영 DB, 운영 S3, capability 활성화는 실행하지 않았다.

## 검증 근거 확인

| 범위 | 명령 | 결과 |
|---|---|---|
| AI 전체 회귀 | `cd apps/ai && TESTCONTAINERS_RYUK_DISABLED=true uv run pytest` | `544 passed`, coverage `90.30%` |
| MinIO raw 복구 | `uv run pytest --no-cov tests/datasets/test_raw_store.py` | `8 passed`; checksum·length·version·byte 복구·동일 key 재사용 |
| local refresh wrapper | `apps/ai/ops/test-run-local-reference-refresh.sh` | `Pass`; source별 secret 경계와 고정 순서 |
| reference docs | `apps/ai/ops/build-reference-docs.sh --check` | `Pass`; 결정성·HTML·secret 검사 |
| property-data | `cd apps/property-data && ./gradlew backendQualityCheck --no-daemon --stacktrace` | `BUILD SUCCESSFUL`; PostGIS persistence·fresh Flyway·coverage·API docs 포함 |
| chat-bff | `cd apps/chat-bff && ./gradlew chatBffQualityCheck --no-daemon --stacktrace` | `BUILD SUCCESSFUL` |
| change classifier | `.github/scripts/test-classify-changes.sh` | `Pass` |
| DB role 경계 | `infra/postgres/verify-service-boundaries.sh` | `Pass` |
| chatbot runner | `infra/chatbot/test-run-local-chatbot.sh` | `Pass`; preflight와 secret 비노출 |
| signed JWT transport | `infra/chatbot/test-signed-jwt-e2e.sh` | `Pass`; JSON/SSE, wrong issuer `401`, property 회귀 |
| base Compose | `docker compose -f infra/docker-compose.local.yml config --quiet` | 검증용 placeholder 환경에서 `Pass` |
| chatbot Compose | `docker compose -f infra/docker-compose.local.yml -f infra/docker-compose.chatbot.yml config --quiet` | 검증용 placeholder 환경에서 `Pass` |

`docker-compose.chatbot.yml`은 base의 `postgis`, `redis`, `api`, network를 확장하는
overlay이므로 단독 `config`는 유효한 배포 topology가 아니다. 실제 runner 및 문서와
같이 base와 결합해 검증했다. 검증용 placeholder는 command-local 값이며 파일이나
artifact에 저장하지 않았다.

## 인수 시나리오

- 실제 PostGIS Testcontainer와 MinIO container를 모두 사용했다.
- 30만 normalized row의 peak memory `<256MiB`, semantic `NoChange`, changed
  candidate만 staging, incomplete bundle 미게시를 전체 AI 회귀에서 확인했다.
- raw object는 file stream upload 후 `HEAD` checksum·length·version을 검증했고,
  저장된 version을 지정해 원본 byte를 복구했다.
- 대규모점포 CSV와 철도 XLSX prepared bundle은 bytes 재적재 없이 owner-only temp로
  1MiB chunk 추출하며 artifact 길이·SHA-256을 검증했다. 9MiB fixture와 checksum,
  manifest, target 권한 실패를 확인했다.
- publication rollback, active pointer 보존, runtime/base table 접근 거부,
  importer/migrator/runtime role 경계를 확인했다.
- 800m, 1km, 1.5km 경계와 NEIS/Sbiz fuzzy match 거부, 철도 occurrence 보존·역
  병합, 공개 JSON/SSE citation shape를 확인했다.
- 문서 artifact 결정성과 secret pattern 0건을 확인했다.

## TDD 근거

- MinIO 항목은 누락된 production behavior가 아니라 누락된 integration evidence였다.
- 새 실제 MinIO test는 기존 production 구현에서 즉시 `Pass`해 characterization으로
  고정했다. RED를 만들기 위한 production 변경은 하지 않았다.

## 검증 공백과 잔여 위험

- 1GiB raw 파일과 21GiB ephemeral storage를 실제 할당하지는 않았다. 9MiB artifact
  file streaming 구조와 30만 normalized row memory gate로 검증해 성능·자원 항목
  `0.5`를 감점했다.
- source별 이용조건, 실제 taxonomy/release URL, 실제 row·freshness·coverage, S3 운영
  복구, spatial query p95와 live golden은 readiness 단계의 blocker다.
- readiness `9.0/10` 미만 capability는 runtime allowlist에 추가하지 않았다.

`api-contract: compatible`

검증 범위: AI raw store·projection/view·grounding·allowlist, property/chatbot 공개 계약,
DB role 및 Compose secret 주입 경계를 점검했다.

`security-audit: 지적사항 = none`
