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

```text
Slice: S2 — 기존 capability 정적 composition
요구사항: 기존 property·학교·학원·철도·대규모점포 capability를 고정 순서 handler catalog로 실행하면서 기존 답변 계약을 보존한다.
구현 범위: CapabilityCatalog, 여덟 typed handler, repository protocol·CapabilityResult 경계, GroundedChatbotEngine orchestration 축소
지적사항: none — 반복 전체 실행 중 공용 dataset DB 오류가 일시 발생했으나 실패 44건 격리 재실행과 깨끗한 전체 재실행에서 모두 통과해 코드 회귀가 아님을 확인
검증 근거 확인: 최초 RED에서 capability_handlers import 실패 확인; `uv run pytest --no-cov tests/property_chat/test_capability_catalog.py tests/property_chat/test_grounded_engine.py` 35 passed; `uv run pytest --no-cov tests/property_chat` 240 passed; 실패 집합 `uv run pytest --no-cov --lf` 44 passed; `uv run pytest -q` 641 passed, coverage 90.27%; `git diff --check` Pass; secret pattern 검사 Pass
검증 공백: none
과설계 판정: 생성자 고정 catalog만 사용하며 runtime plugin·reflection·workflow engine·handler 상속 계층 없음
코드 스멜: engine.py 899줄(S1 1,265줄, 최초 기준 1,334줄 미만); legacy `_reference_observers`와 capability `if/elif` dispatch 제거
공통화 결정: readiness와 source별 claim policy는 concrete handler에 유지하고 EvidenceFact pure builder 주입만 공유; presenter base class 없음
UI 연결: 공개 JSON/SSE shape와 S1 factList 경로를 변경하지 않아 기존 web 소비자 회귀 없음
잔여 위험: source별 direct factList 확장은 이번 동작 보존 refactor 범위에서 의도적으로 추가하지 않음
점수: 9.5/10 Pass — 신규 사용자 기능 없이 내부 composition만 변경한 Slice라 UI 실행 근거 0.5 감점
api-contract: compatible
security-audit: 지적사항 = none
commit: `refactor(ai): compose grounded capability handlers`
```

```text
Slice: S3 — 공식 어린이집 수집·projection
요구사항: 공식 어린이집 응답을 raw-first로 수집하고 검증된 facility_point projection으로 게시하며 동일 semantic 재수집은 NoChange가 된다.
구현 범위: cpmsapi030 XML collector·strict adapter·전용 key/env 경계·기존 lifecycle/S3 raw/spool/facility_point writer·childcare_center_fact read view·운영 문서
지적사항: none — 자체 리뷰에서 generic `craddr`를 도로명주소로 오인한 mapping과 provider XML 재직렬화로 raw checksum 복구가 깨지는 문제를 발견해 commit 전에 각각 generic address attribute와 원문 private raw 보존으로 수정
검증 근거 확인: 최초 RED에서 childcare module import 3건 실패 확인; `uv run pytest --no-cov tests/datasets/test_childcare_client.py tests/datasets/test_childcare_adapter.py tests/datasets/test_childcare_ingest.py tests/datasets/test_childcare_projection.py` 27 passed; `TESTCONTAINERS_RYUK_DISABLED=true uv run pytest -q` 669 passed, coverage 90.05%; `ops/test-run-local-reference-refresh.sh` Pass; `ops/build-reference-docs.sh --check` Pass; `git diff --check` Pass
검증 공백: 신청 승인 전이므로 live provider schema·전국 시군구 scope·row total·좌표 coverage는 검증하지 못함; 이 공백은 source PENDING과 capability 비활성으로 차단
과설계 판정: 기존 lifecycle·secure raw·normalized spool·facility_point·GIST/index를 재사용; 신규 scheduler·framework·source 전용 공간 table 없음
코드 스멜: source mapping과 forbidden-field 경계를 feature-local로 유지; provider가 제공하지 않는 pagination/total을 추측하는 fallback 없음
공통화 결정: 기존 projection writer registry와 refresh composition만 재사용하고 childcare collector/adapter/policy는 별도 유지
UI 연결: 없음 — typed read view와 CLI status만 제공하며 source/capability는 활성화하지 않음
잔여 위험: 승인된 key와 공식 현재 시군구 코드 목록으로 live completeness·전국/지역별 좌표 coverage·실제 schema를 확인해야 publication 및 S4 활성화 가능
점수: 9.0/10 Pass — fixture 기반 정확성·계약·보안 gate는 충족했으나 승인 대기 중인 live readiness 1.0 감점
api-contract: compatible
security-audit: 지적사항 = none — 전용 key는 request 조립 시에만 사용하고 keyed URL/provider 오류 body는 raw metadata·log·reason에 남지 않으며 연락처·대표자는 private raw 외 normalized/projection/evidence에 노출하지 않음
commit: `feat(ai): collect official childcare snapshots`
```

```text
Slice: S4 — 어린이집 grounded answer
요구사항: 단지 주변 공식 운영 어린이집의 유형·정원·직선거리·기준일만 grounded text와 factList/v1로 제공한다.
구현 범위: childcare_lookup plan schema·정적 handler·PostgresChildcareRepository·source-specific fact/policy validator·기존 factList presenter·비활성 runtime composition
지적사항: none — 최초 전체 coverage 89.93% Fail에서 지역 coverage 미확인·partial·DB/role fail-closed 분기 테스트를 보강해 90.16%로 통과; artifact label 경계 초과가 텍스트 답변까지 실패시키는 위험은 artifact 무시 fallback으로 수정
검증 근거 확인: 최초 RED에서 `childcare_centers` module import 실패 확인; childcare handler/repository 27 passed; 관련 parser/composition 포함 좁은 회귀 116 passed; PostGIS publication→runtime read 통합 2 passed; 관련 전체 322 passed; `TESTCONTAINERS_RYUK_DISABLED=true uv run pytest -q` 703 passed, coverage 90.16%; `npm run lint` 0 errors(기존 warning 8); `npm run test` 238 passed; `npm run build` Pass; reference docs check와 `git diff --check` Pass
검증 공백: 승인 key 기반 live snapshot이 없어 실제 provider row로 capability 응답을 실행하지 않음; exact runtime allowlist에서 childcare_lookup을 제외해 노출 차단
과설계 판정: childcare 전용 typed repository/handler만 추가하고 generic facility handler·plugin·registry·신규 DB table·source별 React component 없음
코드 스멜: engine.py 1,021줄로 최초 기준 1,334줄 미만; artifact rendering은 기존 presenter와 web FactListArtifactView 재사용
공통화 결정: facility_point/GIST와 AnswerDocument/FactListArtifact lifecycle만 재사용하고 정원·입소 금지 정책과 typed SQL은 childcare feature-local 유지
UI 연결: 기존 factList/v1 renderer로 최대 5개 시설을 표시하며 malformed/oversize label은 artifact만 숨기고 text/evidence 유지
잔여 위험: S3 live completeness·좌표 coverage·schema 검증과 source readiness 9.0 이상 전에는 activation 불가
점수: 9.5/10 Pass — code·fixture·PostGIS·UI 회귀는 충족했으나 live source readiness 미검증 0.5 감점
api-contract: compatible
security-audit: 지적사항 = none — 전화·팩스·홈페이지·대표자·현원은 query/fact/artifact에 없고 SQL은 parameterized·100..2,000m·최대 5건·3초 timeout으로 bounded
commit: `feat(ai): ground official childcare facts`
```

```text
Slice: S5 — Chatbot → Kakao 지도 action
요구사항: 병원·어린이집 지도 질문이 검증된 단지 좌표 기반 one-shot action을 만들고 사용자가 누를 때만 기존 Kakao 주변시설 overlay를 연다.
구현 범위: kakao_place_search typed plan/handler·showNearbyCategory/v1 strict validator·BFF passthrough·IndexedDB optional action·ChatActions·MapApp props command·기존 viewport nearby-place hook 연결
지적사항: none — 자체 리뷰에서 새 placeCategory가 기존 reference planner fixture를 거부하는 additive 호환 문제와 공식 의료기관으로 오인할 수 있는 문장을 발견해 각각 legacy field-set 허용과 map action claim policy로 수정
검증 근거 확인: 최초 RED에서 QueryPlan place_category 부재와 action button→map command 미연결 확인; 공식 병원 오인 문장 추가 RED 1건 확인 후 GREEN; 관련 AI 회귀 138 passed; `uv run pytest` 712 passed, coverage 90% 이상; `./gradlew chatBffQualityCheck --no-daemon --stacktrace` Pass; `npm run lint` 0 errors(기존 warning 8); `npm run test` 243 passed; `npm run build` Pass; `npm run test:live-api` Pass; `git diff --check` Pass
검증 공백: none — 실제 Kakao 장소 결과는 기존 live API smoke로 확인하고 action의 click 전 0회·click 후 category 1회·one-shot은 deterministic test로 분리 검증
과설계 판정: showNearbyCategory 한 종류와 MapUiCommand 한 종류만 구현; AI Kakao client·전역 event bus·Redux·generic command registry 없음
코드 스멜: engine.py 1,060줄로 최초 기준 1,334줄 미만; ChatbotPanel.tsx 459줄로 최초 기준 470줄 미만; action renderer와 map command 소비를 별도 component/workspace에 유지
공통화 결정: 기존 useMapViewport와 useViewportNearbyPlaces lifecycle만 재사용하고 action contract validator와 one-shot props command만 추가
UI 연결: center 이동·level 4·category 하나로 교체·selected POI 해제; 실행 버튼은 focus를 유지하며 지도 오류가 chat panel을 닫지 않음
잔여 위험: runtime exact allowlist에는 kakao_place_search를 포함하지 않아 S9 readiness activation 전 사용자에게 노출되지 않음; childcare category 역시 S3 live readiness 전 활성화 금지
점수: 9.5/10 Pass — deterministic UI·live public API는 검증했으나 capability activation 이후 실제 browser action smoke는 S9에서 재확인 필요
api-contract: compatible
security-audit: 지적사항 = none — action은 citation factId와 marker-safe 국내 좌표에 묶이고 외부 문자열은 React text node로만 렌더링하며 Kakao 전화·URL·장소 응답은 chat request/message/IndexedDB archive에 저장하지 않음
commit: `feat(web): connect chatbot place actions to map`
```

```text
Slice: S6 — 단지 비교 vertical slice
요구사항: 2~4개 단지를 동일 cutoff·365일·전용면적 ±1.0㎡·동일 단위로 비교하고 partial 가격 cell을 보존한 표와 텍스트를 제공한다.
구현 범위: comparison typed plan/정적 plan handler·RecentThreeTradeBasis·property/rail/retail batch query·comparison fact policy·comparisonTable/v1 strict adapter·접근 가능한 가로 scroll table UI
지적사항: none — 자체 리뷰에서 동일 역·점포가 두 단지에 잡힐 때 source factId가 서로 다른 거리를 합칠 수 있는 중간(Medium) 위험을 발견해 단지별 comparison factId로 분리한 뒤 재검증; 최초 전체 coverage 89.97% Fail은 값 객체 실패 경계와 cutoff 없음 테스트를 추가해 90.17%로 통과
검증 근거 확인: 최초 RED에서 comparison domain module 부재와 web artifact adapter 미지원 확인; comparison domain/handler 17 passed; property·철도·점포 PostGIS batch 통합 21 passed; 관련 AI 회귀 118 passed; `TESTCONTAINERS_RYUK_DISABLED=true uv run pytest` 734 passed, coverage 90.17%; `./gradlew chatBffQualityCheck --no-daemon --stacktrace` Pass; `npm run lint` 0 errors(기존 warning 8); `npm run test` 246 passed; `npm run build` Pass; `ops/build-reference-docs.sh --check` Pass; `git diff --check` Pass
검증 공백: none
과설계 판정: comparison 전용 typed row builder와 실제 두 execution shape를 위한 정적 plan handler만 추가; metric plugin·generic table registry·workflow engine·신규 DB table 없음
코드 스멜: engine.py 1,119줄로 최초 기준 1,334줄 미만; ChatbotPanel.tsx 459줄 유지; table renderer를 별도 ComparisonTableArtifactView로 분리
공통화 결정: comparison과 다음 recommendation이 실제 공유할 RecentThreeTradeBasis·CandidatePoint만 pure domain object로 추출; UI metric registry는 만들지 않음
UI 연결: 2개 기본 폭, 3~4개 내부 가로 scroll, 첫 metric column sticky, 실제 th/scope, 확인 불가 이유·cutoff·365일·면적·표본 기준 표시, winner 색상 없음
잔여 위험: comparison은 exact runtime allowlist 밖에 있어 S9 activation 전 노출되지 않음; 학생·영유아 조건부 row는 S8 범위
점수: 10.0/10 Pass
api-contract: compatible — 기존 request/URL/result/uiSummary/JSON/SSE 의미와 property-data 공개 계약은 변경하지 않고 uiArtifacts의 documented comparisonTable/v1만 채움
security-audit: 지적사항 = none — SQL은 parameterized이고 이름 2~4·거래 3건·source별 batch 1회·3초 observation timeout으로 bounded; LLM은 row/value/order를 만들지 못하며 외부 문자열은 React text node로만 표시
commit: `feat(ai): compare grounded apartment facts`
```

```text
Slice: S7 — 추천 기본 정책: 가격·철도·대규모점포
요구사항: 지역·예산·전용면적을 만족하고 최근 거래 3건이 있는 단지만 예산으로 hard filter한 뒤 철도·대규모점포 접근성의 결정론적 조건 충족도를 제공한다.
구현 범위: recommendation typed plan·정적 plan handler·RecommendationPolicy·지역 descendant/최근 3건 bounded SQL·rail/retail batch observation·recommendationCards/v1 strict adapter·접근 가능한 score detail UI
지적사항: none — 자체 findings-first 리뷰에서 필수 조건 누락이 provider 오류로 평탄화되는 중간(Medium) 위험과 조건 충족 후보 0건을 observation fact 없이 supported로 처리하는 중간(Medium) 위험을 발견해 각각 observation 전 구조화 누락 안내와 recommendation scope fact로 수정 후 재검증
검증 근거 확인: 최초 RED에서 recommendation domain module 부재와 web artifact adapter 미지원 확인; 누락 조건 오류와 verified-zero 근거에 대한 추가 RED 확인; recommendation policy/handler 10 passed; property candidate descendant·latest-three·20회 p95 200ms 미만 통합 검증 포함 property repository 14 passed; 관련 AI 회귀 61 passed; `TESTCONTAINERS_RYUK_DISABLED=true uv run pytest` 747 passed, coverage 90.14%; `./gradlew chatBffQualityCheck --no-daemon --stacktrace` Pass; `npm run lint` 0 errors(기존 warning 8); `npm run test` 249 passed; `npm run build` Pass; `ops/build-reference-docs.sh --check` Pass; `git diff --check` Pass
검증 공백: none
과설계 판정: 고정 v1 scorer와 recommendation 전용 typed artifact/handler만 추가; generic scoring framework·metric plugin·UI registry·신규 DB table·property/AI DB join 없음
코드 스멜: engine.py 1,145줄로 최초 기준 1,334줄 미만; ChatbotPanel.tsx 459줄 유지; RecommendationPolicy는 DB·HTTP·LLM 의존 없는 pure object이며 card renderer를 별도 component로 분리
공통화 결정: S6의 CandidatePoint·RecentThreeTradeBasis만 실제 공통 domain object로 재사용하고 recommendation SQL·fact mapping·표현은 feature-local 유지
UI 연결: 단지명·조건 충족도·최근 거래·3건 중앙값을 기본 표시하고 `<details>`에서 weight·points·직선거리를 공개; 가격 winner·투자 badge·HTML 렌더 없음
잔여 위험: recommendation은 exact runtime allowlist 밖에 있어 S9 activation 전 노출되지 않음; 학생·영유아 theme와 동적 25점은 S8 범위
점수: 10.0/10 Pass
api-contract: compatible — 기존 chatbot URL/request/result/uiSummary/JSON/SSE와 property-data 공개 계약은 변경하지 않고 uiArtifacts의 documented recommendationCards/v1만 채움
security-audit: 지적사항 = none — SQL은 parameterized되고 region descendant·후보 100·최종 5·365일·±1.0㎡·3초 observation timeout으로 bounded; secret·provider body·Kakao 결과·연락처를 저장하거나 표시하지 않으며 외부 문자열은 React text node로만 렌더링
commit: `feat(ai): recommend budget-qualified apartments`
```

```text
Slice: S8 — 질문 반응형 학생·영유아 조건
요구사항: 현재 추천·비교 질문에 명시된 교통·학생·영유아·쇼핑 조건만 검증해 기본 철도 10점·대규모점포 5점을 유지하면서 동적 25점을 결정론적으로 배분한다.
구현 범위: 고정 lifestyle theme detector·school level detector·RecommendationPolicy 동적 weight·학교/Sbiz/어린이집 batch observation·비교 조건부 row·recommendationCards activeThemes/details·strict web adapter/UI badge
지적사항: none — 자체 findings-first 리뷰에서 어린이집 batch가 전국 unknown region을 0으로 고정하던 중간(Medium), 과거 S7 card의 activeThemes 부재를 거부하던 중간(Medium), 추천 제한 문장·시설명 validator와 reason allowlist가 불완전하던 중간(Medium) 지적사항을 각각 aggregate coverage, legacy normalization, 좁은 name validator·stable reason 등록으로 수정 후 재검증
검증 근거 확인: 최초 RED에서 lifestyle theme module 부재와 동적 점수 미지원 확인; coverage 89.98% 최초 gate 실패 후 정상 verified-zero 경계를 추가 검증; recommendation/comparison 관련 19 passed; 학교·Sbiz·어린이집 Postgres batch 통합 검증 Pass; `TESTCONTAINERS_RYUK_DISABLED=true uv run pytest` 765 passed, coverage 90.04%; `./gradlew chatBffQualityCheck --no-daemon --stacktrace` Pass; `npm run lint` 0 errors(기존 warning 8); `npm run test` 252 passed; `npm run build` Pass; `ops/build-reference-docs.sh --check` Pass; `.github/scripts/test-classify-changes.sh` Pass; `git diff --check` Pass
검증 공백: none — live childcare 호출은 승인 대기 중이므로 실행하지 않았고 runtime activation도 하지 않음; collector·projection·batch query는 sanitized fixture와 실제 Postgres schema로 검증
과설계 판정: 고정 enum·trigger와 pure calculator만 추가; 사용자 weight·규칙 DSL·theme plugin·metric registry·신규 DB table·workflow engine 없음
코드 스멜: engine.py 1,183줄로 최초 기준 1,334줄 미만; ChatbotPanel.tsx 459줄 유지; lifestyle 계산·theme 검증은 독립 pure module이며 source별 repository field mapping은 feature-local 유지
공통화 결정: recommendation score와 comparison raw fact가 실제 공유하는 lifestyle observation 계산·EvidenceFact만 공통화하고 표현 객체와 source별 batch SQL은 분리 유지
UI 연결: card에 활성 theme badge와 실제 weight·학교 level별 최근접 위치·Sbiz 교육업소 수·공식 어린이집 수/최근접 거리를 text node로 표시; comparison은 같은 observation의 조건부 row만 추가
잔여 위험: childcare live service 승인·전국 coverage readiness가 통과하기 전 YOUNG_CHILD와 childcare capability 활성화 금지; recommendation/comparison exact runtime allowlist 역시 S9 activation 전 비활성
점수: 10.0/10 Pass
api-contract: compatible — 기존 chatbot URL/request/result/uiSummary/JSON/SSE와 property-data 공개 계약을 변경하지 않고 documented artifact에 additive field만 추가; S7 archive의 activeThemes 부재는 빈 theme로 정상화
security-audit: 지적사항 = none — 질문 theme는 LLM 제안을 현재 질문 trigger로 재검증하고 후보 100·batch query·3초 timeout을 유지; secret·provider body·전화·팩스·홈페이지·Kakao 결과를 저장/표시하지 않으며 외부 문자열은 React text node만 사용
commit: `feat(ai): score explicit lifestyle conditions`
```

```text
Slice: S9 — 복합 질문·최종 UI
요구사항: 최대 4개 Capability가 포함된 질문을 정적 순서로 분해해 fragment별 observation·draft·grounding을 독립 검증하고 일부 unavailable이어도 검증된 text·artifact·action을 보존한다.
구현 범위: QueryPlanBundle 1..4/중복 merge·strict OpenAI bundle schema·fragment별 3초 observation과 병렬 bounded 실행·CompoundAnswerDocument·public fragments/executionSummary·JSON/SSE 동일성·지원 중인 학원+철도 복합 예시
지적사항: none — 자체 findings-first 리뷰에서 compound artifact byte cap으로 제외된 artifactId가 fragment에 남을 수 있는 중간(Medium) 위험과 S8 학생 citation의 비표준 `A+B` grade/`+` dataset version으로 web strict adapter가 응답을 거부하는 중간(Medium) 위험을 발견해 각각 accepted ID 교집합과 `B`/colon identifier로 수정 후 재검증
검증 근거 확인: 최초 RED에서 QueryPlanBundle import 실패와 OpenAI compound plan parser 거부 확인; compound/model/http 17 passed; comparison+HOSPITAL action 통합 Pass; property_chat 333 passed; `TESTCONTAINERS_RYUK_DISABLED=true uv run pytest` 773 passed, coverage 90.02%; `./gradlew chatBffQualityCheck --no-daemon --stacktrace` Pass; `npm run lint` 0 errors(기존 warning 8); `npm run test` 252 passed; `npm run build` Pass; property-data `backendQualityCheck` Pass; reference docs·change classifier·service boundary·chatbot local runner·local reference refresh Pass; 실제 서명 JWT JSON/SSE·잘못된 issuer 401·property 회귀 E2E Pass; `git diff --check` Pass
검증 공백: 신규 comparison·recommendation·hospital map action의 OpenAI live golden은 승인된 case catalog와 비용 실행 승인이 없어 not run; exact runtime allowlist에서 제외해 미검증 기능 노출을 차단함. 승인 대기 childcare live 호출도 not run이며 source/capability를 비활성 유지함
과설계 판정: 1..4 fan-out과 ordered aggregation만 추가; DAG·dependency solver·workflow engine·dynamic plugin·event bus 없음
코드 스멜: engine.py 1,195줄로 최초 기준 1,334줄 미만; ChatbotPanel.tsx 459줄 유지; compound serialization은 AnswerDocument에, plan 정규화는 immutable QueryPlanBundle에 유지
공통화 결정: 기존 single AnswerDocument를 fragment 단위로 재사용하고 실제 복합 응답에만 CompoundAnswerDocument를 추가; generic workflow/renderer는 만들지 않음
UI 연결: 과거 single/text-only 대화는 fragments=[] 호환 유지; web은 검증된 artifact/action만 기존 전용 component로 표시하고 빈 화면 예시는 현재 지원되는 academy_lookup+rail_station_lookup 복합 질문으로 교체
잔여 위험: runtime exact allowlist의 comparison·recommendation·hospital map action 활성화는 승인된 live case·대표 질문 Pass·별도 activation commit이 필요; childcare lookup/YOUNG_CHILD는 provider 승인·실제 schema·전국/지역별 좌표 coverage 확인 전 활성화 금지
점수: 9.5/10 Pass — deterministic compound/JSON/SSE/browser-unit 경로는 검증했으나 신규 activation의 signed JWT live golden은 별도 gate
api-contract: compatible — 기존 URL/method/request/result/uiSummary와 single response fragments=[]를 유지하고 compound에서만 documented fragments를 additive하게 채움
security-audit: 지적사항 = none — fragment 최대 4·artifact 8/65,536 bytes·action 4·answer 20,000자·기존 전체 timeout을 유지하고, provider/internal/grounding 오류를 partial로 위장하지 않으며 prompt·SQL·credential·raw body를 fragment에 노출하지 않음
commit: `test(ai): verify grounded lifestyle integration`
```
