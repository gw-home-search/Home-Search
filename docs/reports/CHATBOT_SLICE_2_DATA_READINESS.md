# Slice 2 데이터 준비 보고서

기준일: 2026-07-16
판정: `Pass` — 데이터 생명주기 기반만 통과. 사용자 질문 Capability 활성화는 `0`건이다.

## 출처와 이용 조건

- 제공자: Home Search repository test fixture
- 범위: `apps/ai/tests/fixtures/dataset_lifecycle/*.json`
- 이용 조건: repository test 전용, 외부 배포 데이터 아님
- 기준일: 2026-07-15
- 수집일: 테스트 clock 기준 2026-07-16
- schema/좌표계: `fixture-v1`, `EPSG:4326`
- 개인정보: 없음

## 원본과 품질 결과

| fixture | checksum (SHA-256) | 원본 행 | 정상 행 | 격리 행 | 결과 |
|---|---|---:|---:|---:|---|
| `valid.json` | `c9af075e38a3a7001bc0d6df71d4f019943a083edd8c98541c95bb203851a83f` | 1 | 1 | 0 | publish `Pass` |
| `invalid-coordinate.json` | `e0ff61de42dcc0e680365607ca0a6840a083a4abadb6bbb53b9e8bcb2e0e9e14` | 1 | 0 | 1 | `INVALID_COORDINATE` |
| `missing-required.json` | `b09b5e91e457d65490035764b95d6efb15e55e02f1e84f848ba078155b128cd6` | 1 | 0 | 1 | `REQUIRED_FIELD_MISSING` |

추가 고정 시나리오로 checksum 재수집, source contract 변경 재검증, 고유키 중복,
허용 범위 밖 행 수, stale 기준일, 원본 parse 실패, 게시 transaction 실패, 이전
publication rollback을 검증했다. 원본은 validation 전에 checksum과 수집 시각을
포함해 저장되며, 실패 행과 사유는 삭제하지 않는다.

## 커버리지와 공간 품질

- 전국 시도·시군구 커버리지: 해당 없음. 이 fixture는 운영 시설 원장이 아니다.
- 좌표 유효성: 정상 fixture `1/1`; 잘못된 위도 fixture `0/1`, 격리 확인.
- 공간 matching: 해당 없음. Slice 2는 polygon/시설 매칭을 수행하지 않는다.
- 결측·중복·이상 증감: 잘못된 행은 warning evidence와 quarantine으로 기록한다.
  contract의 최대 격리 비율, 절대 행 수, 이전 active 대비 증감률을 넘으면 blocking
  issue로 전환해 게시를 막는다.

## 활성화 범위

- 활성화 가능한 질문 유형: 없음
- 검증된 기반: raw → staging → validate → publish, checksum 멱등성, atomic active
  pointer 교체, publication failure rollback, 명시적 이전 snapshot rollback
- 금지 범위: fixture를 학교·교통·의료 또는 부동산 사실로 답변에 사용하지 않는다.

## 검증 공백과 잔여 위험

- `home_search_ai` 운영 인스턴스와 운영 credential은 아직 생성·적용하지 않았다.
- 실제 공공 dataset의 라이선스, 전국 커버리지, schema 변형, 대용량 성능은 각 후속
  데이터 Slice에서 별도로 검증해야 한다.
- 현재 parser는 Slice 2 고정 JSON envelope인 `{"rows": [...]}`만 지원한다. 실제
  CSV/API 응답은 출처별 adapter가 추가되기 전까지 수집할 수 없다.
- migration은 별도 `HOME_AI_MIGRATOR_DSN` 명령으로만 실행하며 runtime startup에서
  자동 적용하지 않는다.
- 장기 저장되는 `acquisition_url`에는 query, fragment, user-info를 허용하지 않아
  API credential이 source contract metadata에 포함되지 않게 한다.
