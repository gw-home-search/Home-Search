# 챗봇 답변 품질·성능 gate

기준일: 2026-08-07

## 판정

- 품질: `Quality Pass` — strict 101/120 (기준 96/120)
- 성능: release snapshot 검증 전이므로 production 배포 금지
- 공개 property API URL·response shape: 변경 없음
- DB migration·stored data 의미: 변경 없음

## 품질 검증 근거

seeded Postgres와 결정형 provider fixture에서 120문항을 legacy·graph로 각각
실행했다. 한 문항은 두 경로가 모두 v2 의미 조건을 통과해야 strict `Pass`다.

- strict: 101/120 (기준 96/120)
- broad overview: 6/6
- provider failure: 8/8
- ambiguity: 9/11 이상
- compound: 10/12 이상
- legacy·graph 의미 불일치: 0
- 동일 문장·동일 절반 반복: 0
- citation closure 실패: 0

검증 명령:

```bash
cd apps/ai
TESTCONTAINERS_RYUK_DISABLED=true \
  uv run pytest tests/property_chat/test_answer_first_http_catalog_integration.py \
  -q --no-cov
uv run pytest
```

단일 integration 파일은 전체 source coverage를 대표하지 않으므로 `--no-cov`로
행동 gate를 실행하고, 전체 `uv run pytest`에서 repository coverage gate를
별도로 적용한다.

## 성능 gate와 검증 공백

chat-bff terminal log는 질문·답변·context를 기록하지 않고 bounded `intent`,
capability, outcome, latency만 기록한다. budget-production alarm은 다음 p95를
강제한다.

| intent | p95 상한 |
|---|---:|
| `DIRECT_PROPERTY` | 10초 |
| `COMPLEX_OVERVIEW` | 10초 |
| `REFERENCE_COMPOUND` | 15초 |
| `TREND` | 10초 |
| `COMPARISON` | 15초 |
| `RECOMMENDATION` | 20초 |
| 전체 | 30초 |

seeded catalog 240회 실행은 약 25초였지만 작은 fixture와 fake provider 결과이므로
release p95 근거로 승격하지 않는다. production snapshot에서 클래스별 warm-up 5회와
최소 30회 반복, baseline 대비 20% 회귀, DB pool wait와 provider 호출 수를 확인하기
전에는 release candidate나 production 승인으로 판정하지 않는다.

## 잔여 위험

- production snapshot·실 provider latency를 아직 측정하지 않았다.
- 일반 production과 budget-production의 official web 활성 설정 불일치는 그대로
  release blocker다.
- production live provider 호출과 배포는 이 검증에 포함하지 않았다.

## Release 재시도 기록

- `v1.0.64` release workflow `31146535375`는 ECR immutable tag
  `home-search/public-gateway:1.0.64` 충돌로 중단됐다.
- 일부 SHA·SemVer 이미지가 먼저 게시됐으므로 기존 tag나 이미지를 삭제하지 않고,
  `v1.0.64`를 재실행하지 않는다.
- 사전검사에서 `home-search/public-gateway:1.0.65`와 `:1.0.66`도 이미 존재함을
  확인했다. 두 tag 역시 덮어쓰거나 삭제하지 않는다.
- 20개 release repository에서 충돌이 없음을 확인한 `v1.0.67`을 새 commit에
  생성해 release pipeline을 다시 실행한다.

## 보안 영향

security-audit: 지적사항 = none

질문 원문, 답변, prompt, SQL, provider 응답, token, cookie, DSN을 metric label이나
terminal log에 추가하지 않는다. BFF는 전체 응답 128KiB와 auto-run action 1개,
artifact/action/citation reference closure를 검증한다.
