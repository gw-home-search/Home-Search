## 요약

상태: Pass
장시간 data-only export의 Docker bridge PostgreSQL 연결에 TCP keepalive를 강제하고, snapshot cleanup 오류가 최초 export 오류를 덮어쓰지 않게 합니다.

## 작업 범위

- migration PostgreSQL child process: 기존 `PGOPTIONS`를 보존하면서 10초 keepalive 정책 강제
- snapshot lifecycle: cleanup 실패는 최초 예외의 note로 보존
- tests: keepalive override 순서와 최초 예외 우선순위 회귀 검증

## 사용 skill

| phase | skill | role | evidence |
| --- | --- | --- | --- |
| plan/execute | home-search-harness | workflow guard | Draft PR lint/publish |
| execute | $tdd | behavior change | 최초 RED와 최소 GREEN |
| checkpoint | $api-contract | compatibility | public API 및 manifest contract 무변경 |
| recover | $systematic-debugging | failure isolation | 동일 cold-index boundary 2회 재현 |
| gate | $code-review | findings-first review | 오류 우선순위와 regression 점검 |
| gate | $security-audit | connection/secret review | password 비노출과 option 경계 확인 |

## TDD 근거

최초 RED: 장시간 export child 환경에 TCP keepalive가 없고, body의 `primary export failure`가 cleanup의 `snapshot holder failed`로 대체됐습니다.
예상 RED 실패: Docker bridge 무응답 구간에서 연결 생존을 보장할 설정이 없으며 최초 장애 원인을 관측할 수 없습니다.
최소 GREEN: 기존 `PGOPTIONS` 뒤에 `tcp_keepalives_idle=10`, `tcp_keepalives_interval=10`, `tcp_keepalives_count=3`을 고정하고 cleanup 오류는 최초 예외 note로 보존합니다.

## 검증

검증:
- `python3 -m unittest -v infra/migration/test_data_only_migration.py` = pass (18 tests)
- `infra/migration/test-data-only-migration-integration.sh` = pass (snapshot/export/import/reconcile 통합 계약)
- `infra/migration/test-run-local-data-only-export.sh` = pass (local runner secret·path 경계)
- `python3 -m py_compile infra/migration/data_only_migration.py infra/migration/test_data_only_migration.py` = pass (Python syntax)
- `.github/scripts/test-classify-changes.sh` = pass (change classifier)
- `infra/postgres/verify-service-boundaries.sh` = pass (DB role/credential boundary)
- `infra/test-compose-config.sh` = pass (compose interpolation)
- `docker compose -f infra/docker-compose.local.yml config` = pass (`infra/test-compose-config.sh`의 격리 sentinel 환경에서 검증)
- `.codex/harness/home --self-test` = pass (launcher self-test)
- `python3 .codex/harness/home_flow.py --self-test` = pass (workflow self-test)
- `git diff --check` = pass (whitespace 오류 없음)

## 계약 영향

영향 없음

계약 영향: compatible — 공개 API URL/method/field/unit, data-only allowlist, manifest format과 dataset 순서를 변경하지 않습니다.

## 보안 영향

보안 영향: migration DB 연결 keepalive와 내부 오류 보존만 추가하며 secret 값·Terraform state·artifact 경계를 변경하지 않고 기존 production root와 공개 API·저장 식별자 의미는 변경하지 않음.
security-audit: 지적사항 = none

## 주요 위험

주요 위험: 없음
reviewer: 지적사항 = none

## 다음 행동

다음 행동: Draft PR CI 후 merge하고 새 immutable release tag의 backup digest로 export를 다시 실행합니다.

## 체크리스트

- [x] main merge 자동화 없음
- [x] main push 없음
- [x] integration branch만 push
- [x] draft PR
- [x] public API URL/response 영향 확인
- [x] DB migration 실행 내역 확인
- [x] Open API 호출 내역 확인
- [x] secrets 저장 없음
