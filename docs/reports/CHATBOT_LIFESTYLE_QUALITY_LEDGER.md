# 생활 인프라 비교·추천 chatbot 품질 ledger

## 범위와 판정 기준

이 ledger는 학교 위치, Sbiz 학원 접근성, 공식 어린이집, 철도,
대규모점포, 단지 비교·조건 추천, Kakao 지도 action의 구현 품질을 Slice별로 한 곳에서
추적한다. source별 실제 데이터 readiness는
`docs/reports/reference/readiness/*.md`를 재사용하며 이 파일에 복제하지 않는다.

property-data 공개 URL과 응답은 변경하지 않는다. chatbot JSON/SSE는 기존
`uiArtifacts`, `uiActions`, `fragments` 빈 array를 검증된 값으로 채우는 additive
계약만 허용한다. 통학구역·학교군·학생·교사 지표, HIRA 수집, 시설 품질 평가,
미래가격·수익성·투자 추천은 비범위다.

각 Slice는 정확성·데이터 안전 2.0, 공개 계약 1.5, TDD·검증 1.5, 범위·최소성
1.0, 응집도·공통화 1.0, 실패·성능·운영 1.0, 보안·개인정보 1.0,
UI·문서·소비자 품질 1.0의 총 10점으로 평가한다. `9.0/10` 이상이고 계약·TDD·보안
감점과 치명(Critical)·높음(High)·중간(Medium) 지적사항, 필수 검증 공백이 없어야
commit할 수 있다.

## Slice ledger

```text
Slice: G0 — 범위·공개 계약·품질 ledger
요구사항: 지원 근거와 구조화 UI/action의 additive 공개 계약을 구현 전에 고정한다.
구현 범위: chatbot docs, AI operator docs, 단일 품질 ledger
지적사항: none
검증 근거 확인: `apps/ai/ops/build-reference-docs.sh --check` Pass; `uv run pytest --no-cov tests/test_http_contract.py tests/datasets/test_reference_contract_config.py` 23 passed; `./gradlew chatBffQualityCheck --no-daemon --stacktrace` Pass; `git diff --check` Pass; link/secret pattern 검사 Pass
검증 공백: none — 순수 문서 Slice이므로 First RED 면제
과설계 판정: 세 artifact와 한 action만 허용; generic renderer·mapPoints·chart 없음
코드 스멜: none
공통화 결정: runtime abstraction 없음
UI 연결: 계약만 정의; runtime 영향 없음
잔여 위험: 이후 Slice가 payload validator와 unknown-type fallback을 구현해야 함
점수: 9.5/10 Pass — runtime 동작을 검증하지 않는 문서 Slice 특성으로 UI 실행 근거 0.5 감점
api-contract: compatible
security-audit: 지적사항 = none
commit: `docs(ai): define grounded lifestyle answer gates`
```

후속 Slice는 완료할 때 같은 필드 순서로 이 ledger에 한 항목씩 추가한다.

```text
Slice: S1 — 구조화 답변 최소 vertical path
요구사항: complex_identity가 동일 EvidenceFact 기반 factList/v1을 텍스트와 함께 반환·저장·표시한다.
구현 범위: AI AnswerDocument/FactListPresenter, web strict adapter·IndexedDB optional artifact·FactList UI; BFF는 JsonNode passthrough 유지
지적사항: none — macOS case-insensitive module 이름 충돌은 GREEN 전에 artifactContract.ts로 수정
검증 근거 확인: 최초 RED에서 AI uiArtifacts=[]와 web adapter/storage/UI 누락 확인; `uv run pytest --no-cov tests/property_chat tests/test_http_contract.py` 244 passed; `uv run pytest` 640 passed, coverage 90.16%; `./gradlew chatBffQualityCheck --no-daemon --stacktrace` Pass; `npm run lint` 0 errors(기존 warning 8); `npm run test` 238 passed; `npm run build` Pass; `git diff --check` Pass
검증 공백: none
과설계 판정: factList 한 종류만 구현; registry·reflection·dynamic import 없음
코드 스멜: engine.py 1,265줄(기준 1,334 미만), ChatbotPanel.tsx 443줄(기준 470 미만)
공통화 결정: response와 archive 두 실제 소비자가 공유하는 artifact validator만 추출; presenter base class 없음
UI 연결: ChatMessageBody와 ChatArtifacts 분리, React text node 렌더, unknown/malformed fallback 유지
잔여 위험: 현재 renderer는 의도적으로 factList/v1만 지원; lint warning 8건은 변경 전 기존 항목
점수: 10.0/10 Pass
api-contract: compatible
security-audit: 지적사항 = none
commit: `feat(ai): return grounded answer artifacts`
```
