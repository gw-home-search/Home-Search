# 챗봇 Capability Registry

## 목적

이 문서는 챗봇이 어떤 질문을 어떤 근거로 답할 수 있는지 결정하는 기준이다.
`kosa-team5`의 질문과 실행 흐름은 질문 유형을 식별하는 참고 자료로만 사용했다.
해당 구현 코드, fixture, POI 파일은 이 저장소로 복사하지 않는다.

Capability는 `필수 데이터셋`, `최소 근거 등급`, `허용 주장`, `금지 주장`,
`신선도`, `대체 답변`을 모두 충족해야 활성화할 수 있다. 서비스 코드가 존재해도
Data Readiness가 충족되지 않으면 사용자에게 지원 중인 기능으로 표시하지 않는다.

## 상태

- `지원`: 구현과 데이터가 모두 준비되었고 골든 질문 검증을 통과했다.
- `제한 지원`: 문서에 명시한 mode·조건·source 조합만 활성화되었고 나머지 조합은
  unavailable로 종료한다.
- `데이터 준비 중`: 질문 유형은 승인되었으나 구현 또는 active snapshot이 없다.
- `미지원`: 현재 확보 가능한 근거로 안전하게 답할 수 없거나 제품 범위 밖이다.

Slice 0 종료 시점에는 챗봇 runtime이 없으므로 `지원` Capability는 없다.

## 프로토타입 질문 흐름 분류

| 참조 흐름 | Home Search Capability | Slice 0 상태 | 비고 |
|---|---|---|---|
| `simple_lookup` | `complex_identity`, `recent_trade_lookup` | 데이터 준비 중 | 지역 가격 순위는 현재 미지원 |
| `price_trend` | `price_trend` | 데이터 준비 중 | 과거 관찰 집계만 허용 |
| `comparison` | `comparison` | 데이터 준비 중 | 같은 기준일·단위의 항목만 비교 |
| `recommendation` | `recommendation` | 제한 지원 | `CRITERIA`의 학원·철도·학교 조건만 활성; BUDGET·쇼핑은 readiness 대기 |
| 복합 질문 | `compound_question` | 데이터 준비 중 | 하위 Capability를 독립 검증 |
| 미래 가격·주관적 학군·개인 법률 판단 | 미지원 Capability | 미지원 | 대체 가능한 근거 질문을 안내 |

## 근거 등급

| 등급 | 의미 | 사용 범위 |
|---|---|---|
| `A` | 기존 부동산 DB 또는 공식기관 원본을 검증해 게시한 snapshot | 수치, 조건 검색, 비교, 추천 필수 조건 |
| `B` | 공식기관 API의 조회 시점 응답 | 조회 시각을 표시한 수치·상태 답변 |
| `C` | Kakao Local 등 상용 검색 API의 조회 시점 응답 | 주변 장소 발견과 보조 설명 |
| `D` | allowlist 공식 웹페이지에서 확인한 최신 비정형 문서 | 인용과 보충 설명 |
| `미지원` | 출처·범위·신선도를 보장할 수 없는 정보 | 추정 및 사실 주장 금지 |

출처가 공식이라는 이유만으로 자동으로 `A`가 되지 않는다. 원본 보존, checksum,
필수 필드, 좌표, 중복, 커버리지, 기준일 검사를 통과해 active snapshot으로 게시된
버전만 `A`로 사용할 수 있다.

## Registry

| Capability | 대표 질문 | 상태 | 필수 데이터셋 | 최소 등급 | 허용 주장 | 금지 주장 | 신선도 | 대체 답변 |
|---|---|---|---|---|---|---|---|---|
| `complex_identity` | “잠실엘스 어디야?” | 지원 | `home_search.ai_read` 단지·지역·marker-safe 좌표 | A | 식별된 단지명, 주소, 공개 좌표 | 동명 단지를 임의 선택 | Slice 3 감사 통과 active view | 후보를 나열하고 지역 등 추가 조건 요청 |
| `recent_trade_lookup` | “전용 84㎡ 최근 실거래 5건” | 지원 | `ai_read` 정상 거래·단지 | A | 실제 거래일, 전용면적, 금액, 층 | 호가·시세로 재해석, 미래 가격 | 최신 거래일과 coverage를 응답에 표시 | 조회 기간과 조건에서 거래 없음 안내 |
| `price_trend` | “최근 1년 가격 흐름” | 지원 | `ai_read` 정상 거래 | A | 동일 조건의 월별 집계와 거래량 | 미래 추세 단정, 표본 부족 은폐 | 요청 종료일 이하 active data | 계산 불가 기간과 최소 표본 부족 설명 |
| `comparison` | “A와 B 가격·세대수 비교” | 데이터 준비 중 | 비교 항목별 동일 버전의 A/B 데이터 | A | 같은 기간·단위로 계산 가능한 항목 | 기준일·평형이 다른 수치의 직접 비교 | 모든 필수 항목이 freshness 통과 | 비교 가능한 항목만 답하고 누락을 분리 |
| `recommendation` | “영등포구 500세대 이상, 학원 우선 후보” | 제한 지원 | 부동산 A, Sbiz 학원 B, 철도 A, 학교 A | A | `CRITERIA` mode에서 세대수 선필터와 명시한 학원·철도·학교 우선순위로 계산한 후보 | BUDGET·쇼핑 실행, 절대평가, 수익 보장, LLM 임의 점수 | 사용한 모든 필수 dataset 통과 | 비활성 조건과 후보 0건 이유 설명 |
| `school_location` | “주변 운영 중 초등학교” | 지원 | 학교 위치·운영상태 snapshot | A | 학교 유형, 운영 상태, 직선거리 | 학교 품질·서열·진학 성과 | 반기 갱신 + grace 31일 이내 | 공식 snapshot 미준비 안내; Kakao는 별도 보조 결과 |
| `elementary_attendance_zone` | “배정 초등학교는?” | 미지원 | 현재 범위에 통학구역 polygon 없음 | 미지원 | 학교 위치 질문으로 전환 안내 | 직선거리로 배정학교 단정 | 해당 없음 | 통학구역은 현재 지원 범위 밖임을 안내 |
| `middle_high_school_zone` | “어느 학교군이야?” | 미지원 | 현재 범위에 학교군 polygon 없음 | 미지원 | 학교 위치 질문으로 전환 안내 | 특정 학교 진학 보장 | 해당 없음 | 학교군은 현재 지원 범위 밖임을 안내 |
| `academy_registry_summary` | “이 시군구의 공식 등록 학원은 몇 곳?” | 데이터 준비 중 | NEIS 학원·교습소 active snapshot | A | 시도교육청+시군구 기준 등록 총수·운영 수·관측일 | 반경·거리, 교육 품질·성과 | source 계약의 수시 갱신 SLA | 공식 snapshot 미준비 또는 지역 식별 불가 안내 |
| `academy_lookup` | “단지 800m 안 교육업소는?” | 지원 | Sbiz 교육업소 active snapshot, 선택적 NEIS exact match | B | 지정 반경의 교육업소 위치와 직선거리; exact match 성공 시 공식 등록 상태 | Sbiz 수를 공식 등록 학원 수로 표현, fuzzy match, 교육 품질 | 월간 관측 + grace 15일 이내 | 지정 조건에서 확인되지 않음 또는 snapshot 미준비 안내 |
| `education_metrics` | “학생·교사 수는?” | 미지원 | 현재 범위에 학생·교사 지표 없음 | 미지원 | 학교 위치와 학원 접근성 질문 안내 | 학생·교사 수 추정, 자체 학교 서열화 | 해당 없음 | 교육지표는 현재 지원 범위 밖임을 안내 |
| `rail_station_lookup` | “가까운 역과 노선” | 지원 | 도시철도 역사 snapshot | A | 역명, 노선, 직선거리 | 통근시간·배차·혼잡도 | 연간 갱신 + grace 45일 이내 | 위치 기준선 미준비 안내; Kakao는 탐색 보조만 가능 |
| `hospital_lookup` | “주변 병원 유형” | 미지원 | 현재 범위에 HIRA 병원 원장 없음 | 미지원 | Kakao 지도 탐색 action으로 전환 안내 | 공식 의료기관 현황·유형·진료 가능·의료 품질 주장 | 해당 없음 | `showNearbyCategory(HOSPITAL)`로 지도 탐색만 제공 |
| `childcare_lookup` | “주변 어린이집 유형·정원” | 데이터 준비 중 | 전국어린이집 active snapshot/API | A 또는 B | 공개된 유형·정원·거리 | 입소 가능 여부 | source 계약의 수시/실시간 SLA | 공개 항목 미확인 안내 |
| `kindergarten_location` | “주변 유치원 위치” | 데이터 준비 중 | 승인된 공식 유치원 위치·운영상태 snapshot | A 또는 B | 승인 후 위치·운영상태·직선거리 | 입학 가능 여부·교육 품질; Kakao 결과를 공식 현황으로 표현 | source 계약 승인 후 정의 | 현재는 사용자 실행형 Kakao 지도 탐색만 별도 제공 |
| `kakao_place_search` | “지금 주변 마트·카페” | 데이터 준비 중 | Kakao Local 조회 응답 | C | 검색 시점·반경·페이지 안의 장소 | 지역 전체 개수, 완전한 상권 밀도 | 요청 시각 표시 | “지정 조건에서 확인되지 않음”으로 표현 |
| `redevelopment_official_evidence` | “정비사업 현재 단계” | 데이터 준비 중 | 지자체·국토부 allowlist 공식 문서 | D | 원문에 명시된 단계·게시일 | 사업 확정·수익성 보장 | 문서별 게시일과 확인일 표시 | 공식 원문을 확인하지 못했다고 안내 |
| `compound_question` | “비교+학군+교통+예산” | 데이터 준비 중 | 하위 Capability 전체 | 하위 항목 중 가장 높은 요구 | 독립 검증된 하위 결과의 교집합 | 일부 실패를 전체 성공처럼 표현 | 하위 dataset별 표시 | 성공·부분·불가 항목을 분리 |
| `region_price_ranking` | “강남구 최고가 TOP 5” | 미지원 | 현재 범위에 ranking dataset 없음 | 미지원 | 실제 거래 조회 범위 안내 | 순위표·최고 지역 단정 | 해당 없음 | 단지 또는 지역의 기간별 실거래 질문 제안 |
| `inferred_lifestyle_recommendation` | “4인 가족에게 좋은 집” | 미지원 | 사용자의 명시 조건 없음 | 미지원 | 예산·지역·면적·시설 조건 요청 | 가족 형태만으로 선호·점수 추론 | 해당 없음 | 필수 조건을 다시 질문 |
| `future_price_prediction` | “내년에 얼마나 오를까?” | 미지원 | 없음 | 미지원 | 과거 관찰값과 한계 안내 | 상승률·가격 예측 | 해당 없음 | 과거 거래 추이 질문 제안 |
| `subjective_school_ranking` | “명문 학군 순위” | 미지원 | 없음 | 미지원 | 공개 지표의 정의 설명 | 자체 서열·진학 보장 | 해당 없음 | 측정 가능한 공식 지표 질문 제안 |
| `actual_commute_time` | “출근에 몇 분?” | 미지원 | 검증된 경로·시간 dataset 없음 | 미지원 | 역 직선거리만 별도 Capability로 안내 | 실제 통근시간·배차 추정 | 해당 없음 | 출발·도착과 공식 교통 데이터 준비 필요 안내 |
| `personal_legal_judgment` | “제가 이길까요?” | 미지원 | 없음 | 미지원 | 지원 범위 밖 안내 | 개인 사건 판단·일반 법률 정보 제공 | 해당 없음 | 법률 전문가 상담 권고 |
| `unrelated_general_chat` | 날씨·의료 진단 등 | 미지원 | 없음 | 미지원 | 챗봇 지원 범위 안내 | 근거 없는 일반 답변 | 해당 없음 | 지원 가능한 부동산 질문 예시 제공 |

## 공통 실행 규칙

1. 질문을 하나 이상의 Capability로 분해한다.
2. 각 Capability의 active dataset, 품질 상태, freshness를 확인한다.
3. 하나라도 필수 조건을 통과하지 못하면 해당 Capability 도구를 실행하지 않는다.
4. 도구는 `factId`, 값, 단위, 출처, dataset version 또는 조회 시각, 근거 등급을 반환한다.
5. LLM은 제공된 fact와 limitation만 사용하고, 사용한 `factId`를 구조적으로 반환한다.
6. 서버는 fact 존재, 수치·단위 일치, citation 완전성을 검증한다.
7. 검증 실패는 사용자에게 부분 답변으로 노출하지 않고 provider 실패로 처리한다.

## 활성화 승인 기록

상태를 `지원`으로 바꾸는 변경에는 데이터 준비 보고서, 골든 질문 결과,
계약 검증, `code-review`, `security-audit: 지적사항 = none|listed`를 함께 남긴다.

2026-07-18 기준으로 부동산 3개 Capability의 운영 `ai_read` offline 검증은 모두
통과했다. 승인된 OpenAI live 대표 질문에서 `complex_identity` fact 1건,
`recent_trade_lookup` fact 3건, `price_trend` 월별 fact 6건이 각각 A등급
citation으로 검증됐고 세 Capability의 signed JWT JSON/SSE 근거 의미를 확인했다.

승인된 활성화 변경에서 runtime은 identity-only rollback 또는 exact cumulative
allowlist
`HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity,recent_trade_lookup,price_trend`
까지 허용한다. 누락·순서 변경·오류·미승인 혼합 설정은 전체 비활성으로 처리한다.
근거 기반 금액 표시 claim, live golden, signed JWT JSON/SSE와 Compose preflight를
검증했으므로 세 부동산 Capability를 `지원`으로 유지한다. 상세 근거는
`docs/reports/CHATBOT_SLICE_5B_PRICE_TREND_ACTIVATION.md`에 기록한다.

2026-07-21 `academy_lookup`은 Sbiz 191,250행·좌표 100%, verified raw 복구,
재수집 `NoChange`, 최대 2km p95 `156.927ms`, Sbiz B+NEIS exact A citation과 signed
JWT JSON/SSE를 통과해 `지원`으로 승인했다. reference allowlist는 빈 rollback 또는
`academy_lookup`만 허용하며 학교·NEIS summary·retail·rail은 이 activation에
포함하지 않는다.

2026-07-21 `rail_station_lookup`은 fixed XLSX 1,097 occurrence·좌표 100%, verified
raw 복구, 동일 release 재수집 재사용, 최대 3km p95 `25.565ms`, exact 역 병합과
signed JWT JSON/SSE A등급 citation을 통과해 `지원`으로 승인했다. 승인된 누적
reference allowlist는 `academy_lookup,rail_station_lookup`이며 rail 단독 설정은
fail-closed한다.

2026-07-21 `recommendation`은 explicit `CRITERIA` mode에서 `MIN_UNIT_COUNT`와
시설 조건 `ACADEMY`·`TRANSIT`만 제한 지원으로 활성화했다. 승인된 누적 property allowlist는
`HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity,recent_trade_lookup,price_trend,recommendation`이며
직전 가격·추이 설정은 rollback 값으로 유지한다. reference allowlist는 이미 readiness
`Pass`인 `academy_lookup,rail_station_lookup`만 사용하고 runtime mode gate는
`BUDGET` 관찰을 DB 조회 전에 종료한다. OpenAI live 대표 질문은
`criteria-recommendation-academy-transit`에서 `CRITERIA`, `ACADEMY→TRANSIT`, 근거
4건으로 통과했다. 대규모점포는 전국 좌표
coverage `83.7404%`로 기준 `95%`에 미달하므로 BUDGET 추천·comparison·SHOPPING을
활성화하지 않는다. 어린이집과 유치원도 각각 별도 readiness와 승인 commit 전까지
실행 경로와 allowlist에서 제외한다.

2026-07-21 `school_location`은 전국 12,011행·rejected `0`·17개 교육청·좌표
100%인 `2026-03-20-b148752f1e38` active snapshot, 동일 원본 재수집 멱등성,
runtime p95 `114.066ms`, 실제 observation과 승인된 OpenAI live 대표 질문을 통과해
`지원`으로 승인했다. 승인된 누적 reference allowlist는
`academy_lookup,rail_station_lookup,school_location`이며 학교 단독 또는 순서가 다른
설정은 fail-closed한다. 이 구성에서는 explicit `CRITERIA` 추천의 `SCHOOL` 조건도
같은 공식 snapshot을 사용한다. 통학구역·학교군·품질·서열·도보시간은 계속 미지원이다.
