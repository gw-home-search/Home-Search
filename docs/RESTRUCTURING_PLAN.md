# Backend Restructuring Plan

백엔드 재구조화의 실행 계획과 그 상위 맥락을 한 문서에서 관리한다:

- **이번 PR에서 실행할 것**: `apps/property-data` 내부 Gradle 멀티모듈을
  `core`와 `api`으로 분리하고, `api -> core -> libs/rtms-ingest-core`
  의존 방향을 고정한다.
- **후속 PR에서 검토할 것**: `batch-app` 생성과 RTMS daily refresh의
  Spring Batch run-and-exit 전환 (§5)
- **상위 맥락**: 라이브러리 경계 규칙(§3), MSA 서비스 지도와 진화 로드맵(§8),
  AWS 배포 아키텍처(§9), MSA/Kafka/DB 설계 선행 게이트(§10)

- 상태: `core`/`api` 멀티모듈 분리 반영 — `batch-app`/배치 전환은 후속 검토 대상
- 작성일: 2026-07-06
- 전제: 재작성(rewrite)이 아니라 검증된 코어를 보존하는 구조 이동 + 껍데기 교체(strangler)

## 1. 배경과 목표

### 1.1 프로젝트 컨텍스트

home-search는 `home-server`/`home-client`의 마이그레이션 타깃이며, 미션은
"아파트 실거래 데이터를 수집하고, 안전하게 저장하고, 지도에 표시한다 —
공개 API URL을 보존하면서"다 (`docs/README.md` 체계). 현재 백엔드 자산:

- `apps/property-data`: property-data-service 경계의 현재 단일 Spring Boot. ingest 파이프라인(매칭·정규화·dedupe)은
  test 350+ / persistenceTest 199 GREEN으로 검증된 상태
- `libs/rtms-ingest-core`: 순수 파싱 라이브러리 (includeBuild)
- `apps/ml`: 가격 예측 무상태 추론 (D18)
- `apps/web`: 지도 프론트엔드

### 1.2 무엇이 문제인가 (재구조화의 동기)

1. **배치가 상주 프로세스에 내장돼 있다.** RTMS daily refresh가 api 프로세스의
   `@Scheduled`로 실행되어 — api 배포/재시작이 배치 실행과 결합되고, 실패 시
   재시작 단위가 "전체 재실행"뿐이며, 표적 재수집(특정 월/지역) 수단이 없다.
2. **손으로 만든 배치 골격 약 1,400줄**(`infrastructure/scheduling/rtms`)이
   Spring Batch가 표준으로 주는 것(JobInstance, restart 의미론, 실행 메타데이터)을
   자체 재구현하고 있다. 코어는 자산이지만 이 껍데기는 교체 대상이다 (D5).
3. **배포 단위가 역할을 표현하지 못한다.** 서빙/배치/추론이라는 다른 역할이
   Spring 쪽에서는 jar 하나에 뭉쳐 있어, MSA 로드맵(§8)의 서비스 경계가
   코드에도 배포에도 보이지 않는다.
4. **확장 예정 도메인(user/JWT/OAuth, AI 챗봇)의 자리가 정의돼 있지 않다.**
   자리 없이 확장을 받으면 later-scope가 수집·지도 critical path에 침투한
   레거시의 실패(§5.6, tradeDailyJob 6스텝 체이닝)를 반복하게 된다.
5. **운영 런타임이 로컬 머신뿐이다.** daily 수집이 개발 머신의 compose에
   의존하고 있어 가용성과 데이터 안전(백업)이 사람 손에 걸려 있다.

### 1.3 목표

1. 후속 Gradle 멀티모듈 `core` / `api` / `batch-app` — 모듈 경계가 서비스
   경계(D15)를 비추고, batch-app은 run-and-exit 배포 모드가 된다.
2. daily refresh를 Spring Batch job으로 교체 (§5) — JobInstance 기준 재실행과
   `rtmsBackfillJob` 표적 재수집을 얻는다. 파티션 단위 실패 격리는 D11 조건
   충족 후의 후속 실익이다.
3. AWS 운영 전환 (§9) — EventBridge 스케줄, ECS on EC2, RDS, CI 게이트(D26).
4. 전 과정에서 불변: 공개 API 계약(`docs/API_CONTRACT.md`), 데이터 불변식
   (raw-first / dedupe / failed-match queryability), 테스트 GREEN,
   캐노니컬 문서 동기 갱신 (§6 마감 규칙).

### 1.4 비목표

- `application/ingest/**` 파이프라인 재작성 금지. 한 줄도 바꾸지 않는 것이 기본값.
- 신규 레포/신규 프로젝트 시작 금지. 기존 테스트 자산을 안전망으로 사용.
- 이번 선행 PR에서는 내부 모듈 분리, Java package 변경, DB/Flyway 변경,
  runtime behavior 변경을 하지 않는다.
- trade/map 서비스 분리 아님 (D15) — batch와 api는 같은 bounded context의
  두 실행 모드다.
- user/ai 도메인 구현 아님 — 탄생 수칙(D16)과 청사진(D19)으로 자리만 예약.
- Kafka 브로커 구축 아님 — 표준 선언(D21)과 도입 트리거만 확정.

### 1.5 성공 기준 (인수 기준)

- daily 배치가 EventBridge → ECS RunTask로 실행되고 결과가 Slack으로 보고된다.
- 같은 `runDate` 재실행이 안전하고(멱등), 표적 재수집은 `rtmsBackfillJob`
  파라미터 실행으로 가능하다.
- api 재배포와 배치 실행이 서로 독립이다.
- map/trade 엔드포인트는 batch-app·ml-inference 부재 상태에서도 동작한다
  (예측은 degrade).
- `test` + `persistenceTest` GREEN에 더해 실부팅 스모크(§6)가 통과한다.
- 위 상태가 캐노니컬 문서(ARCHITECTURE / INFRA_AND_ENV / DATA_STORAGE)에
  코드와 모순 없이 반영돼 있다.

### 1.6 완료 작업: runtime split 1차

rename 선행 PR 이후의 첫 runtime split은 `core`와 `api` 멀티모듈을
생성하되, `batch-app`과 신규 `libs/*`는 만들지 않는 범위로 마감한다.
현재 코드는 다음처럼 분류한다.

| 대상 | 판정 | 이번 프리플라이트에서 할 일 |
|---|---|---|
| map/search/detail/trade HTTP API | `api` 실행 모드 | public API URL/응답 shape 유지 대상임을 확인 |
| `application/**`, `domain/**`, persistence/external/cache/observability adapter | `core` | `core-trade`/`core-map`/`core-shared`로 쪼개지 않음 |
| RTMS daily/monthly refresh orchestration | `api` 잔류, `batch-app` 후보 | 이번에는 `api`에 두고 후속 Spring Batch 전환 대상으로 표시 |
| `RawIngestReconciliationRunner`, `TradePartitionMaintenanceRunner`, coordinate readiness | maintenance | API 기능은 아니지만 데이터 복구/운영 안전장치로 보존 |
| region unit count sync, metadata enrichment, match rematch | one-shot 또는 ops | 자동 실행 조건과 후속 batch-app 후보 여부만 분류 |
| `libs/rtms-ingest-core` | `libs` | 유지 |
| VWorld/ODcloud/APIS wire DTO + 순수 파싱 | `libs` 후보 | `libs/geo-core`를 만들지 않음. §3.3 조건 충족 전까지 core 잔류 |
| `*Resolver`/`*Client` 포트 구현체, `*Configuration` | `core` | adapter이므로 libs로 이동하지 않음 |
| prediction 클라이언트/캐시/feature query | `core`의 live-capable 기능 | detail API optional 응답에 연결되어 있으므로 삭제하거나 libs로 빼지 않음 |
| ranking/favorite/alarm/mail/recommendation/heavy analytics | later-scope | critical path에 추가하지 않음 |

이번 1차 산출물은 `core`/`api` Gradle module, Java 소스루트 이동, 자동화
경로 갱신, 테스트 가드다. Spring Batch job 작성, DB/Flyway schema 변경,
public API 변경은 후속 PR에서만 수행한다.

## 2. 확정 결정 사항 (Decision Log)

| # | 결정 | 내용 |
|---|---|---|
| D1 | 모듈 구성 (재개정) | `core` + boot jar 2개(`api`, `batch-app`). 원칙: 모듈 경계는 서비스 경계(D15)를 비춘다. core-trade/core-map·core-shared·core-schema 분할안은 검토 후 폐기 — 사유는 §4 "경계 수단의 역할 분담" |
| D2 | 모듈 이름 | `core` / `api` / `batch-app`. user 스코프 진입 시 `core-user` 추가 (D16) |
| D3 | 패키지명 불변 | 자바 패키지(`com.home.*`)는 그대로 두고 소스루트만 이동. import 변경 0이 원칙 |
| D4 | 신규 libs 생성 보류 | 지금은 `libs/rtms-ingest-core`만 유지. 신규 라이브러리는 §3.3 승격 조건 충족 시에만 |
| D5 | 코어 보존 | ingest 코어는 자산. 교체 대상은 orchestration 껍데기(`infrastructure/scheduling/rtms` 약 1,400줄)뿐 |
| D6 | 전환 방식 | strangler: 신규 Spring Batch job과 기존 `@Scheduled` 경로 병행 검증 후 구 경로 삭제 |
| D7 | enrichment 스케줄러 | `ComplexMetadataEnrichmentScheduler`는 당분간 api 잔류. batch-app 후속 job 후보로만 표시 |
| D8 | `rtms_ingest_run` 유지 | 도메인 운영 증거 테이블. `BATCH_*` 메타데이터로 대체하지 않음 |
| D9 | JobParameters 규약 | daily identifying 파라미터는 `runDate`(yyyy-MM-dd) 하나가 기본값, `baseDealYmd`는 파생. 재실행 workset 검증 전제와 drift 처리 — 함정 상세는 §5.2 |
| D10 | job 카탈로그 | batch-app은 multi-job 그릇. 시작은 `rtmsDailyRefreshJob` + `rtmsBackfillJob` 2개. job 1개 = 책임 1개, 이질적 스텝 체이닝 금지 |
| D11 | 파티션/병렬화 보류 | 첫 전환은 순차 실행. Partitioned Step은 restart/drift 정책 확정 후 도입(§5.3), 병렬화 승격 기준은 §5.5 — 실측 이후로 의도적 보류 |
| D12 | 스케줄 트리거 | AWS EventBridge Scheduler → 컨테이너 실행(ECS RunTask). 전제: 운영 런타임이 AWS(DB 접근 가능). 스케줄 정의는 IaC로 레포에서 버전 관리 |
| D13 | 정책 공유 금지 | 도메인 정책/판단은 서비스 간 라이브러리 공유 금지. 허용 범위(wire client·프리미티브·기술 섀시)와 사유는 §3.5 |
| D14 | feature 경계 규율 | `application/**` feature 패키지 간 직접 import 금지, boundary test로 강제. baseline 예외 1건 포함 상세는 §4 원칙 |
| D15 | 서비스/DB 경계 | 서비스 경계는 데이터 소유권: `property-data`(trade+map 한 몸, DB 하나) / `user`(미래) / `ai`(later-scope). trade↔map DB 분리는 로드맵 제외 — 근거는 §8.1 |
| D16 | user 도메인 탄생 수칙 | user/JWT/OAuth가 스코프에 들어오는 날: `core-user` 모듈 신설 + 전용 `users` 스키마 + 전용 Flyway location + 토큰 발급(user 소유)/검증(기술 섀시, api·BFF) 분리 + 맵 공개 표면은 무인증 유지 |
| D17 | 내부 경계 수단 | core 내부 조망성 = feature 패키지 + D14 테스트, 워크로드 격리 = 배포 모드 분리(batch-app). 역할 분담 상세는 §4 |
| D18 | AI 구성요소 분리 | ml-inference(무상태 추론 함수)와 ai-service(상태 소유 챗봇)는 통합하지 않는다. 역할 정의는 §8.3, 호출 방향은 §8.4 |
| D19 | 챗봇 이식 청사진 | 참조 구현은 kosa-team5/server(읽기 전용), 이식 설계는 `docs/AI_SERVICE_PLAN.md`. 핵심 작업 = feature dao 직접 SQL → `ai_read` 뷰 치환. 지위는 later-scope |
| D20 | 배포 아키텍처 | ECS on EC2 + RDS — 근거·구성·원칙은 §9. 로컬 compose는 의도적 비대칭. D18 재검토 조건: ai-service 운영 6개월 후 Python 2서비스 부담이 실증되고 의존성이 안정화되면 합류 재론 |
| D21 | Messaging 표준 | 비동기 이벤트 표준은 Kafka 의미론(topic, consumer group, at-least-once, DLQ). 운영 런타임은 §10.2에서 비용 실측 후 결정. 요청/응답 경로 대체 금지. **도입 트리거**: 첫 실제 이벤트 소비자 등장 시 — 그 전에 브로커를 세우지 않는다 |
| D22 | 동기/비동기 분리 | 동기(HTTP/SSE)·비동기(Kafka event)의 경계와 금지 방향은 §8.4 단일 매트릭스로 관리. timeout·retry·DLQ·idempotency 정책은 §10.3에서 확정 |
| D23 | Transactional Outbox | DB 변경↔event publish 정합성은 outbox로 보장, at-least-once 전제. `event_outbox`/`processed_event` 스키마는 첫 producer/consumer 구현 전 확정 — 브로커와 무관하게 §10.1에서 선행 가능 |
| D24 | DB 설계 선행 | 새 스키마·서비스·이벤트가 걸린 구현(ai, user, Kafka) 전에 `docs/DATA_STORAGE.md`에서 DB ownership 확정 (산출물 §10.1). 분할 1차·2차는 게이트 비대상 |
| D25 | DB 권한/마이그레이션 | migration owner: `home_search`=api(SQL은 core 소유), `coordinate_source`=source-data, `ai`=ai-service(Alembic), `users`=future user-service. batch-app은 validate, `ai_read`는 SELECT only, ml-inference·web은 DB 권한 없음. 권한표는 DATA_STORAGE.md·INFRA_AND_ENV.md에서 확정 |
| D26 | 배포 전 게이트 | 게이트 목록(필수/데이터 안전/서비스 분리/Kafka)은 §10.4. Kafka 게이트는 첫 producer 도입 시부터 활성. rollback = git SHA 이미지 태그 + migration rollback/forward-fix |

## 3. 외부 라이브러리(`libs/*`) 경계 규칙

기준 선례: `libs/rtms-ingest-core`
(includeBuild + dependencySubstitution, 소비 좌표 `com.home:rtms-ingest-core:0.0.1-SNAPSHOT`).

### 3.1 libs 편입 조건 (전부 충족해야 함)

1. 계약을 외부 세계가 정의한다 (공공 API 스펙, 한국 주소/PNU 체계, Slack API 등).
2. `com.home.domain`, `com.home.application`, `com.home.infrastructure` import 제로.
3. Spring / JDBC / DB 스키마 지식 제로.
4. 위 2·3을 boundary test로 강제한다 (`RtmsIngestCoreBoundary` 선례).

### 3.2 libs 편입 금지

- application 포트 구현체(어댑터), application 타입 변환 코드
- 도메인 정책, 도메인 enum, 매칭/정규화/dedupe 판단
- persistence, Flyway 마이그레이션

`infrastructure/external/**`의 클라이언트들은 application 포트를 구현하는 어댑터이므로
라이브러리 후보가 아니라 core 잔류 대상이다. 라이브러리로 뺄 수 있는 것은
그 아래층(wire DTO + 순수 파싱)뿐이며, RTMS는 이 분리가 이미 완료된 상태다.

### 3.3 라이브러리 승격 조건

다음 중 하나가 실제로 발생하기 전에는 신규 libs를 만들지 않는다.

- 두 번째 독립 소비자가 생긴다 (다른 레포/앱이 같은 wire 로직을 필요로 함).
- 순수 파싱 로직이 커져 Spring 없는 단위 테스트 격리가 실익이 된다.

승격 1순위 예약 후보: geo 파싱(VWorld 좌표/응답 파싱). 그 외 Slack notifier의
HTTP 부분은 규모(~100줄)상 실익이 없어 보류.

퍼블리시(레지스트리 배포)는 소비자가 레포 밖에 생겼을 때만 검토한다.
includeBuild 구조상 퍼블리시 전환 시 소비자 코드는 변경되지 않는다.

### 3.4 현재 코드 판정표

| 대상 | 판정 |
|---|---|
| `libs/rtms-ingest-core` | 유지 (기준 선례) |
| vworld/odcloud/apis 응답 DTO + 좌표·PNU 순수 파싱 | 승격 가능 후보 (§3.6) |
| Slack(Hermes) notifier HTTP 부분 | 승격 가능 후보 (§3.6) |
| `*Resolver`/`*Client` 포트 구현체, `*Configuration` | core 잔류 (어댑터) |
| prediction 클라이언트 (2클래스) | core 잔류 |

### 3.5 MSA 관점의 라이브러리 카테고리

MSA 전환(§8)을 전제하면 라이브러리 카테고리는 4개로 늘어난다.
단, 전부 "그 시점이 오면"이며 선제 생성하지 않는다.

| 카테고리 | 내용 | 예시 | 승격 시점 |
|---|---|---|---|
| ① wire client | 외부 세계가 계약을 정의 | `rtms-ingest-core`(현존), geo 파싱 | 현존 / §3.3 조건 |
| ② shared kernel | 서비스들이 공유하는 도메인 프리미티브 (작고 안정적인 값 타입) | `RtmsLawdCode`, `RtmsDealMonth`(현존 씨앗), PNU 값 타입 | 서비스 실제 분리 시 |
| ③ service chassis | 기술 규약 공통부 | 에러 응답 포맷, 메트릭/로그 네이밍, ops notifier | 두 번째 독립 Java 서비스 등장 시 |
| ④ service contract | 서비스 간 내부 API DTO | 분리된 서비스가 property-data 사실을 동기 조회하는 내부 계약 | 서비스 분리 + 상호 호출 발생 시 |

원칙 (D13): 비즈니스 정책·판단(매칭 정책, dedupe, marker-safe 규칙)은 어떤
카테고리로도 공유하지 않는다. 정책을 jar로 공유하는 순간 규칙 변경마다 전
서비스 락스텝 배포가 필요해지는 분산 모놀리스가 된다. 정책은 소유 서비스
안에만 살고, 다른 서비스는 API(또는 뷰 계약)로 결과만 소비한다.

현실 제약: batch-app은 같은 빌드라 core가 섀시 역할을 하고(퍼블리시 불필요),
FastAPI ai-service는 Python이라 Java 라이브러리를 소비하지 못한다. 따라서
③④의 실제 트리거는 두 번째 독립 Spring 서비스의 등장 — 현실적으로
user-service(D16) — 이다. trade/map 분리는 로드맵에 없으므로(D15) 그 경로로는
트리거가 오지 않는다.

### 3.6 승격 후보 상세: 현황과 목표 형태

#### geo 파싱 (VWorld / ODcloud / APIS)

- 현황: wire DTO는 record로 분리돼 있으나(`VworldParcelCoordinateResponse`,
  `OdcloudAptResponse`, `ApisBldRecapResponse`), 좌표 추출·단지 식별 판단이
  어댑터(`VworldParcelCoordinateResolver`, `OdcloudComplexIdentityResolver` 등)에
  섞여 있어 "라이브러리 층 vs 어댑터 층" 절단선이 코드에 없다.
- 목표 형태: `libs/geo-core`(가칭) — 응답 DTO + 좌표/식별 **파싱**만.
  식별 **판단**(어느 후보를 채택하나, 검증 실패 분류)은 도메인이므로 core 잔류.
- 사용처: coordinate lookup(맵 표면), complex enrichment — 모두 맵 서빙 쪽 feature.
  승격 시점은 Stage 4(서비스 독립 배포)에서 ②shared kernel과 함께 (§8.2).
  단 D15 이후 두 번째 Java 소비자가 나타나지 않을 수 있으며, 그 경우
  core 영구 잔류가 정상이다 (승격 조건 미충족 = 승격 안 함).
- 지금 할 일: 분할 1차(§6)에서 이동만 하고 쪼개지 않는다. 승격 전 준비로
  "어댑터에서 순수 파싱 로직을 사적 메서드가 아닌 별도 클래스로 유지"하는
  규율만 지킨다.

#### Slack(Hermes) notifier

- 현황: 범용 notifier가 아니라 `RtmsDailyRefreshHermesNotifier`가
  `infrastructure/scheduling/rtms`에 package-private로 존재. RTMS daily 전용
  네이밍/이벤트 타입(`rtms-daily-refresh`)이 하드코딩돼 있다.
- 목표 형태 (2단계): 분할 2차(§6-3)에서 batch-app 공용 `BatchSummaryListener`가
  소비해야 하므로 core의 범용 ops notifier로 승격 — 전송(HTTP)과 이벤트
  구성(source/eventType/channel)을 분리하고 RTMS 전용 네이밍 제거.
  이는 보류가 아니라 분할 2차의 실제 작업 항목이다.
- 목표 형태 (최종): ③service chassis의 일부로 두 번째 Java 서비스 등장 시
  `libs/ops-notifier`(가칭) 승격.
- 사용처: batch-app job 결과 알림(전 job 공통), api 운영 알림(향후),
  분리된 각 서비스의 공통 알림 경로.

## 4. 목표 모듈/패키지 구조

```
home-search/
├─ libs/
│   └─ rtms-ingest-core/                  # 유지. 프리미티브 추가 승격도 여기로
├─ apps/
│   ├─ property-data/                         # property-data-service 경계
│   │   ├─ core/                          # property-data-service의 몸체 (boot jar 아님)
│   │   │     com.home.domain.**
│   │   │     com.home.application.**     # feature 패키지 = 내부 경계 (D14)
│   │   │     com.home.infrastructure.persistence/external/cache/observability.**
│   │   │     src/main/resources/db/migration/**       # Flyway SQL
│   │   ├─ api/                       # boot jar ① 상주 웹 서버 = 서빙 모드
│   │   │     infrastructure/web.**  global/error.**
│   │   │     HomeSearchApiApplication
│   │   │     (전환 완료 전까지 infrastructure/scheduling.** 잔류)
│   │   └─ batch-app/                     # boot jar ② run-and-exit = 배치 모드
│   │         com.home.batch.rtms.**      # Spring Batch job/Partitioner/listener
│   │   (미래) core-user/                 # user-service의 몸체. 스코프 진입 시 (D16)
│   ├─ web / ml / rtms-loader / source-data
```

의존 규칙:

```
api  → core        batch-app → core        core → libs/rtms-ingest-core
(미래) core-user는 core와 상호 의존 금지 — 서비스 경계이므로 컴파일러로 강제
```

경계 수단의 역할 분담 (D17):

- 모듈 경계 = 서비스 경계 (D15). core가 한 덩어리인 것은 trade+map이
  한 서비스라는 사실의 정확한 반영이다. core-trade/core-map 분할은 폐기 —
  쓰기 파이프라인(daily 배치)이 region 동기화·coordinate preflight·complex
  metadata를 가로지르므로 워크로드 축과 도메인 축이 평행하지 않다.
- 워크로드 격리(쓰기/읽기)는 배포 모드(api vs batch-app)가 담당한다.
- core 내부 조망성은 feature 패키지 + D14 boundary test가 담당한다.

원칙:

- 모듈 분리는 소스루트 이동이지 패키지 개명이 아니다. diff는 순수 이동으로 유지한다.
- 테스트 소스는 대상 클래스를 따라 이동한다. `test` + `persistenceTest` GREEN이 각 이동의 완료 조건.
- external 어댑터는 core에 둔다. 어댑터를 위한 별도 모듈은 만들지 않는다.
- `application/**`의 feature 패키지(ingest/map/region/complex/coordinate/read/
  prediction) 간 직접 import를 금지한다 (D14). boundary test로 강제하며,
  기지 예외 1건(`coordinate→complex`, `ComplexCoordinateExceptionService`)은
  명시적 baseline으로 기록 후 점진 해소한다. 공유가 필요한 개념은 domain 또는
  프리미티브 승격(§3.5-②)으로 내린다.
- 공용 모듈(core-shared류)은 만들지 않는다. "둘 다 쓰니까 공용 모듈에"라는
  판단이 필요해지면 그 대상은 정책(→소유 feature) 또는 프리미티브(→libs 승격
  검토)이지, 공용 모듈 신설의 근거가 아니다.

## 5. Spring Batch 전환 설계

### 5.1 현재 코드 ↔ Spring Batch 대응

| 현재 | Spring Batch |
|---|---|
| `RtmsIngestRunRecord` + run repository | `JobExecution`/`StepExecution` 메타데이터 (대체 아님, 병존) |
| `RtmsMonthlyRefreshRunStatus` (COMPLETED/PARTIAL/FAILED) | `BatchStatus`/`ExitStatus` |
| `fetchPageWithRetry` + `RtmsMonthlyRefreshRetryPolicy` | faultTolerant step / `RetryTemplate` |
| 페이지 루프 (`hasNextPage`) | paging `ItemReader` |
| lawdCd × lookback월 workset | 초기: 순차 실행 후보. 이후: restart/drift 정책 확정 뒤 Partitioned Step 후보 |
| `notifySlack` | `JobExecutionListener.afterJob` |
| `ScheduledJobExecutionTemplate` | `JobLauncher` + JobInstance 중복 실행 방지 |

### 5.2 Job 카탈로그와 구조

batch-app은 job 여러 개를 담는 그릇이며, 실행 시 `spring.batch.job.name`
(env `SPRING_BATCH_JOB_NAME` 또는 커맨드라인)으로 job을 선택하고
JobParameters를 커맨드라인 인자로 전달한다.

| job | 트리거 | JobParameters | 용도 |
|---|---|---|---|
| `rtmsDailyRefreshJob` | EventBridge 스케줄 | `runDate` (identifying) | 매일 lookback 창 자동 수집. 같은 날 재실행은 동일 workset 검증이 전제 |
| `rtmsBackfillJob` | 수동 실행 | `fromYmd`, `toYmd`, `lawdCds`(선택) — identifying | 특정 기간/지역 표적 재수집·과거 적재. dedupe 멱등성으로 안전 |

두 job은 동일한 월 단위 ingest 의미론을 공유한다. 첫 구현은 기존
`RtmsMonthlyRefreshRunner` 의미론을 보존하는 순차 step/tasklet로 시작할 수
있으며, Partitioned Step은 restart 단위와 plan drift 처리를 확정한 뒤
도입한다. workset 입력(월 범위 × 지역)은 daily는 `runDate`+lookback에서
파생하고, backfill은 파라미터에서 직접 받는다.

```
rtmsDailyRefreshJob  (JobParameters: runDate [identifying])
├─ Step 1: coordinatePreflightStep      # Tasklet. 실패 시 job 즉시 FAILED
├─ Step 2: monthlyIngestStep            # 초기: 순차 실행. 이후 Partitioned Step 후보
│     work unit: (lawdCd × lookback월) 조합
│     실행 의미론 = 기존 refreshMonth() 의미론 (RTMS 페이징 + ingest + run 기록)
├─ Step 3: regionUnitSyncStep           # Tasklet. 실패해도 job은 계속 진행
└─ JobExecutionListener: work unit 결과 집계 → Slack 알림 (기존 formatter 재사용)
```

JobParameters 함정 (D9의 근거): 매 실행에 타임스탬프를 identifying으로 넣으면
모든 실행이 새 JobInstance가 되어 restart가 영원히 동작하지 않고, 반대로
`baseDealYmd`(yyyyMM)만 identifying이면 월초 COMPLETED 이후 그 달 내내 실행이
거부된다. 따라서 daily identifying은 `runDate`(yyyy-MM-dd) 하나를 기본값으로
둔다. 다만 `runDate`만으로는 `lawdCds`, `lookbackMonths`, DB에서 읽는 지역 코드
목록 변경을 감지하지 못하므로, Partitioned Step restart를 도입하기 전에는 최초
실행의 resolved workset snapshot을 재사용하거나 plan hash drift를 감지해 중단하는
방식을 선택해야 한다. workset이 바뀐 수집은 daily restart가 아니라
`rtmsBackfillJob`으로 수행한다.

batch-app 패키지 구조:

```
com.home.batch
├─ common/          BatchSummaryListener(집계→Slack), JobParameters 규약
│                   (Slack 전송은 core의 Hermes notifier 재사용)
└─ rtms/
    ├─ RtmsDailyRefreshJobConfig    # job 정의 + runDate→월범위 파생
    ├─ RtmsBackfillJobConfig        # job 정의 + 파라미터 검증
    ├─ RtmsMonthRegionPartitioner   # Partitioned Step 도입 시 추가
    └─ (worker step은 core의 기존 월 단위 refresh 의미론을 감쌈)
```

### 5.3 설계 원칙

- 청크/처리 단위는 RTMS 페이지 1개를 유지한다. `OpenApiTradeIngestService.ingest(batch)`의
  기존 저장 경계(raw 저장 → 정규화)를 보존하고 내부는 건드리지 않는다.
- Spring Batch worker는 `OpenApiTradeIngestService.ingest(batch)` 호출 전체를 하나의
  outer transaction으로 감싸지 않는다. raw 저장부터 최종 status 갱신까지를 rollback
  대상 하나로 묶으면 raw-first evidence 불변식이 깨진다. 구현은 transactionless tasklet,
  `PROPAGATION_NOT_SUPPORTED`, 또는 동등한 방식으로 기존 경계를 보존해야 하며, raw 저장
  이후 예외가 발생해도 `raw_trade_ingest` row가 남는 테스트를 완료 조건에 포함한다.
- (lawdCd, dealYmd) 단위 restart는 Partitioned Step 도입 시의 후보 실익이다. 첫 전환은
  순차 실행으로 시작할 수 있고, 파티션화를 도입하기 전에는 같은 `runDate` 재실행이 동일
  workset을 대상으로 한다는 snapshot/hash 검증 정책을 먼저 확정한다. dedupe 불변식 덕분에
  동일 월 재실행은 안전하지만, workset drift를 restart 의미론으로 흡수하지 않는다.
- `rtms_ingest_run`은 파티션 스텝 안에서 지금처럼 계속 저장한다.
  `BATCH_*` 테이블 위에는 기능을 짓지 않는다 (D8).
- `BATCH_*` 메타데이터 테이블은 `public`이 아닌 전용 `batch` 스키마에 둔다.
  Spring Batch 자동 DDL 초기화는 끄고 Flyway 마이그레이션으로 생성한다
  (`spring.batch.jdbc.table-prefix` 사용).
- Partitioned Step을 도입한다면 파티션 실행은 순차(`concurrencyLimit=1`)로 시작한다
  (D11, §5.5).
- job 트리거는 "컨테이너 실행 + 파라미터"로 통일한다. api에 job 트리거용
  HTTP 엔드포인트를 만들지 않는다 (모듈 분리 훼손).

### 5.4 실행/배포 모드

기본 경로는 2단계 직행이다 — §6-3에서 batch-app을 신설하고 기존 `@Scheduled`
경로를 병행 검증한다. 1단계(api 내장)는 채택하지 않은 선택적 중간 형태이며
비교를 위해서만 남긴다.

| 구분 | 1단계 (api 내장, 미채택) | 2단계 (batch-app run-and-exit, 기본 경로) |
|---|---|---|
| 트리거 | 얇은 `@Scheduled` → `JobLauncher.run` | EventBridge Scheduler → ECS RunTask (D12). 로컬은 `docker compose run --rm batch-app` 수동 실행 |
| `spring.batch.job.enabled` | `false` (부팅 시 자동 실행 방지) | `true` (부팅 = 실행이 의도) |
| `web-application-type` | `servlet` | `none` |
| job 선택 | 코드에서 지정 | `SPRING_BATCH_JOB_NAME` env / 커맨드라인 |
| Flyway | api이 migrate | batch-app은 validate만. migrate는 api(또는 추후 독립 마이그레이션 잡) |

실행 예시 (2단계):

```
# daily (스케줄러가 실행; 로컬 검증 시 수동)
docker compose run --rm batch-app

# 표적 backfill: 2026-05 강남구만 재수집
docker compose run --rm -e SPRING_BATCH_JOB_NAME=rtmsBackfillJob \
  batch-app fromYmd=202605 toYmd=202605 lawdCds=11680
```

restart 운영: 표적 재수집은 `rtmsBackfillJob`이 담당한다. daily job은 lookback 창 +
dedupe 멱등성 덕분에 다음날 실행으로 자연 치유되므로 restart는 편의 장치다.
Partitioned Step을 도입할 경우에만 같은 `runDate` 재실행을 실패 work unit restart로
해석하며, 이때 최초 실행과 재실행의 resolved workset이 같다는 snapshot/hash 검증을
통과해야 한다.

### 5.5 병렬화 승격 기준 (의도적 보류, D11)

병렬화는 지금 결정하지 않는다. Partitioned Step도 첫 전환의 필수 조건이 아니며,
도입하더라도 먼저 순차 실행으로 시작한다. 재론 조건:

- 기존 `rtms_ingest_run`의 `startedAt`/`completedAt` 실측으로 순차 러닝타임을
  먼저 확인한다. Partitioned Step 도입 후에는 `BATCH_STEP_EXECUTION`이 work unit별
  시간을 기록한다.
- 러닝타임이 운영 창(03:00 시작 → 06:00 이전 종료)을 벗어날 때만 검토한다.
- 검토 시 상한 2~4. 전제: `RateLimitedRtmsApartmentTradeClient`가 파티션 간
  공유 인스턴스로 남아 전역 리미터로 동작할 것.

### 5.6 레거시(home-server) 참조 결과

읽기 전용 참조. 계승과 차별을 명시한다.

계승:

- `spring.batch.job.name` 기반 job 선택 (`SPRING_BATCH_JOB_NAME`)
- `web-application-type: none` + compose `restart: "no"` (run-and-exit)
- `initialize-schema: never` + Flyway로 `BATCH_*` 생성
- 월×지역 Partitioner 뼈대와 `groupSize`(지역 묶음) 아이디어
- 공용 `BatchSummaryListener` + Slack 알림 패턴

차별 (레거시의 교훈):

- 프로파일 스위치(`@Profile("batch")`) 대신 물리적 모듈 분리 (batch-app jar)
- 한 job에 이질적 스텝 체이닝 금지 — 레거시 `tradeDailyJob`은 수집→트렌드→랭킹→
  메일 6스텝 직렬로, 메일 실패가 수집을 FAILED로 만들고 later-scope가 수집의
  critical path에 들어갔다. job 1개 = 책임 1개 (D10)
- 별도 `home_batch` DB(이중 데이터소스) 대신 같은 DB의 `batch` 스키마
- job 트리거용 HTTP 컨트롤러(`TradeAlarmJobController`) 금지
- 스케줄 정의를 호스트 crontab에만 두지 않는다 — 레거시는 배치 실행 주체가
  레포 어디에도 없었다. 스케줄은 IaC로 버전 관리 (D12)

### 5.7 배포 전제 (확정)

운영 런타임은 AWS로 확정 (D20, §9). D12의 EventBridge Scheduler → ECS RunTask는
원문 그대로 유효하다. 남은 세부: 태스크 정의 IaC 형태(Terraform vs 스크립트)는
배포 구현 착수 시 결정.

## 6. 진행 순서

```
0. 현 브랜치 정리        진행 중 미커밋 변경 매듭 (커밋/머지는 사용자가 직접)

1. 설계 md 확정          이 문서 + docs/AI_SERVICE_PLAN.md를 docs/README.md
                         캐노니컬 목록에 등록

1.3 [선행 rename]        apps/api → apps/property-data 경로 rename.
                         Java package, Flyway, DB schema, runtime behavior,
                         Gradle 멀티모듈 구조는 변경하지 않는다.
                         자동화/문서의 현재 경로만 apps/property-data 기준으로 갱신.

1.5 [베이스라인]         모듈 분리 전 ./gradlew test persistenceTest 실행·기록.
                         (persistenceTest는 로컬 PostGIS 필요 — compose 기동 후 실행)
                         이사 전 GREEN이 있어야 이사 후 GREEN이 증거가 된다
                         재수집 불가 데이터 백업 1회 확보: coordinate_source·
                         enrichment 손작업 산물·매칭 증거를 pg_dump로 백업하고
                         위치를 기록한다. 구조 이동의 안전망은 테스트 GREEN과
                         데이터 백업 두 겹이다 — §6-6 이관 시점까지 미루지 않는다

2. [분할 1차]            core + api 모듈 2개로 분리. batch-app은 만들지 않음
                         순수 파일 이동, 동작 변화 0
                         scheduling 패키지는 api에 그대로 잔류.
                         @Scheduled 빈은 전부 api 소속 — external/complex의
                         ComplexMetadataEnrichmentScheduler는 패키지 이웃
                         (resolver/config는 core행)과 달리 api에 둔다 (D7).
                         D3에 따라 패키지명은 유지, 소스루트만 분리
                         착수 첫 작업: 참조 지점 전수 그렙 체크리스트 생성 —
                         레포 전체에서 apps/property-data 경로·gradle 태스크명 검색
                         (.codex hooks/harness — 특히 pr_evidence.py의 경로
                          프리픽스와 backendQualityCheck 태스크명,
                          .agents/skills — backend-api·security-audit의
                          경로/클래스명 전제, backend-quality-gate.toml,
                          infra compose·Dockerfile, ops/, perf/, local-runtime)
                         핵심 작업: build.gradle(278줄) 해부 — test/persistenceTest/
                         jacoco/REST Docs/quality gate 태스크의 모듈 분배.
                         테스트 공용 픽스처는 java-test-fixtures로 공유 (복사 금지)
                         완료 조건: test + persistenceTest 전부 GREEN
                         + 실부팅 스모크 — compose로 api 기동 → health +
                         지도 엔드포인트 1회. 테스트 GREEN만으로는 빈 배선
                         실패를 못 잡는다 (@ConditionalOnBean 실부트 함정 전례)
                         완료 시: ARCHITECTURE.md·INFRA_AND_ENV.md의 구조 서술과
                         검증 명령(:api:bootRun 등)을 같은 PR에서 갱신

2.6 [경계 테스트]        D14 boundary test 도입 (feature 간 import 금지,
                         coordinate→complex 1건은 명시적 baseline)
                         분할 1차와 같은 PR 또는 직후 소형 PR

3. [분할 2차]            batch-app 신설 (core 의존) + rtmsDailyRefreshJob
                         신규 작성 (§5). 초기 구현은 순차 실행 가능,
                         Partitioned Step은 restart/drift 정책 확정 후 도입
                         batch 스키마(BATCH_*) Flyway 마이그레이션 포함 —
                         인프라 배관이므로 D24 게이트 대상 아님 (§10 서두)
                         rtms-loader의 흡수/유지/폐기 결정 (§8.1 비고)
                         기존 @Scheduled 경로는 api에 살아 있는 채 병행 검증.
                         병행 기간 Slack 알림에 출처(legacy|batch)를 표기해
                         이중 알림 혼선을 막는다
                         검증: 신규 job 수동 실행 → rtms_ingest_run 결과를
                         기존 경로 실행분과 비교 (dedupe 불변식으로 이중 실행 안전)
                         완료 조건: 로컬 batch-app 컨테이너 실행 → job COMPLETED
                         + rtms_ingest_run 비교 일치 + test/persistenceTest GREEN
                         완료 시: INFRA_AND_ENV.md에 batch 실행/검증 명령 갱신

4. 구 경로 비활성화       병행 검증 통과 후 @Scheduled 경로를 플래그로 끈다
                         (코드는 유지). 완전 삭제는 7(컷오버) 이후 —
                         EventBridge가 생기기 전까지 로컬 daily 자동 실행의
                         트리거는 @Scheduled뿐이므로, 대체 트리거 가동 전에
                         지우면 daily 수집이 무인 공백 상태가 된다.
                         병행 검증 기간이 끝나도 daily 자동화가 필요한 동안은
                         @Scheduled를 켠 채 유지해도 된다 (신규 job은 수동/
                         backfill 용도로 병존)

--- 여기까지 Stage 1 완료. 이하 Stage 2 (AWS 운영, §9) 실행 단계 ---

5. [CI 신설]             GitHub Actions: test+persistenceTest 게이트 → 이미지
                         빌드 → ECR push (git SHA + latest). D26 게이트의 실행
                         주체가 이 단계에서 처음 생긴다. 레거시 workflow 계승
                         + `-x test` 금지 교정 (§9.3)
                         persistenceTest용 PostGIS는 CI 서비스 컨테이너로 제공

6. [프로비저닝+이관]     IaC: ECS 클러스터·태스크 정의·EventBridge·RDS (§9)
                         데이터 이관 — 재수집 가능과 불가를 구분한다:
                         · trade/raw ingest → rtmsBackfillJob으로 재구축 가능
                         · coordinate_source, enrichment 손작업 산물, 매칭 증거
                           → 재수집 불가. dump/restore 계획 필수
                         (D26의 backup/rollback 기준과 함께 §10.1에서 상세 확정)

7. [컷오버]              D26 게이트 전체 통과 → DNS/FRONTEND_URL 전환 →
                         EventBridge 스케줄 활성화 → 로컬 daily 배치 중지
                         → 운영 안정 확인 후 api에서 scheduling/rtms
                         완전 삭제 (§5.4의 2단계 모드 완성, D5의 1,400줄 회수)
```

각 단계는 독립 PR로 진행하고, 구조 이동(2)과 신규 작성(3)을 한 PR에 섞지 않는다.
각 단계 완료 시 이 문서(§6)와 캐노니컬 문서의 해당 서술을 같은 PR에서 갱신한다 —
캐노니컬 우선순위(ARCHITECTURE.md 등)가 이 계획보다 높으므로, 갱신을 미루면
문서 체계가 코드와 모순되는 상태로 남는다.

## 7. 관련 확정 원칙 (이 계획의 상위 제약)

- single-writer의 "writer"는 프로세스가 아니라 소유권 경계(코드베이스 + Flyway)다.
  api과 batch-app은 같은 경계의 두 프로세스이므로 원칙 위반이 아니다.
- 스키마 주인은 하나: `home_search`의 Flyway 히스토리는 core 한 곳에서만 관리한다.
- FastAPI AI 서비스(챗봇/RAG)는 later-scope이며 구현은 이 문서 범위 밖.
  설계 청사진은 `docs/AI_SERVICE_PLAN.md`에 작성됨 (D19) — 참조 구현 이식 매핑,
  `ai_read` 뷰 계약, `ai` 스키마, 단계적 이식 순서 포함. 착수는 re-scope 결정 이후.
- 공개 API URL/응답 형태는 이 재구조화에서 변경하지 않는다 (`docs/API_CONTRACT.md` 준수).

## 8. MSA 진화 로드맵

이 재구조화는 MSA의 사전 단계다. 서비스 경계는 "따로 떠 있는가"가 아니라
데이터 소유권으로 긋고, 분리는 실측 병목이 확인된 뒤에만 진행한다.

### 8.1 서비스 지도 (데이터 소유권 기준, D15)

경계는 두 종류이며 혼동하지 않는다. **모듈 경계**(core ↔ 미래 core-user)는
서비스 경계를 비추는 컴파일 수준 경계이고(D1), **서비스·DB 경계**는 데이터
소유권이다. trade와 map은 데이터가 한 몸이므로(`complex_id` 불변식, read의
교차 조인, 마커 가격) 서로 다른 서비스가 되지 않으며, 둘 다 core 안의
feature 패키지로 산다 (D17).

| 서비스 | 구성 | 소유 데이터 | 상태 |
|---|---|---|---|
| property-data-service | core (몸체) + api(서빙 모드) + batch-app(배치 모드) | `home_search` DB: raw ingest, normalized trade, 매칭 증거, region/complex/좌표, `rtms_ingest_run` | 현재 구축 중 |
| user-service | `core-user` (미래) | `users` 스키마 → 전용 DB (D16) | 미래, 스코프 진입 시 |
| ai-service | FastAPI (미래) | `ai` 스키마 + vector index. 사실은 `ai_read` 뷰로 구독 | later-scope, 청사진: `docs/AI_SERVICE_PLAN.md` (D19) |
| ml-inference | apps/ml FastAPI (D18) | **없음** — 무상태 추론 함수. 모델 아티팩트 파일만. 피처·예측 캐시는 property-data 소유 | 현존 운영 |
| (선례) coordinate_source | 독립 조회 DB. 적재·운영 도구는 apps/source-data (D25 migration owner) | PNU 좌표 | 이미 분리됨 |

서비스 지도 밖 보조 도구의 지위:

- `apps/source-data`: coordinate_source의 적재·운영 도구. 서비스가 아니라
  운영 유틸리티이며 D25의 owner 지정과 일치. 유지.
- `apps/rtms-loader`: 독립 RTMS 적재 실행기(job planner/executor). 미래
  `rtmsBackfillJob`(D10)과 역할이 겹칠 수 있으므로, 분할 2차(§6-3) 시점에
  흡수/유지/폐기를 결정하고 여기 비고를 갱신한다.

api의 web 레이어는 서비스가 늘어나는 시점에 api-gateway/BFF 역할
(라우팅 + 토큰 검증)을 겸하는 후보다.

run-and-exit batch-app은 "쓰기/읽기 워크로드 격리"를 DB 분리 없이 배포 모드
분리로 달성한다. 읽기 스케일이 필요해지면 리드 레플리카가 다음 수단이며,
이 역시 소유권 분리가 아니다.

### 8.2 진화 단계

```
Stage 0  현재: 단일 boot 프로젝트 + libs/rtms-ingest-core + apps/ml(ml-inference, D18)
Stage 1  멀티모듈 (§6-2): core / api / batch-app + D14 boundary test
Stage 2  batch run-and-exit 배포 분리 (§6-3,4) + AWS 운영 실행 (§6-5~7, D12, §9)
Stage 3  [스코프 진입 이벤트, 순서 무관·독립]
         - user-service: core-user + users 스키마 + JWT/OAuth (D16)
         - ai-service: FastAPI + ai_read 뷰 계약 + ai 스키마 (re-scope 결정 필요)
Stage 4  물리 분리: user/ai 스키마를 전용 DB로 승격, 서비스 독립 배포.
         property-data 내부의 trade↔map DB 분리는 로드맵에 없다 (D15) —
         프로젝션 동기화 비용을 정당화할 실측 근거가 나타나면 그때 별도 재론
```

라이브러리 카테고리(§3.5) 활성화 시점 매핑:

- Stage 1~2: ①wire client만 (현존 rtms-ingest-core 유지, 신규 생성 없음)
- Stage 3 (user 진입 시): ③service chassis 첫 후보 — 토큰 검증 필터(auth-support)
- Stage 4 (서비스 독립 배포 시): ②shared kernel (`geo-core`, lawdCd/PNU/dealYmd
  값 타입), ③`ops-notifier`·에러 포맷, ④service contract 순으로 승격

### 8.3 AI 구성요소의 역할 정의 (D18)

AI 구성요소는 둘이며 MSA 지위가 다르다. 구분 기준은 상태(데이터) 소유 여부.

| | ml-inference (apps/ml, 현존) | ai-service (미래, later-scope) |
|---|---|---|
| 정체 | 무상태 추론 계산기 — "피처 IN → 예측값 OUT" 함수 | 상태를 소유하는 대화 서비스 (챗봇/RAG) |
| 소유 데이터 | 없음. DB 무접속, 모델 파일만 | `ai` 스키마(세션·청크·임베딩) + vector index |
| 피처/사실 획득 | 받는다 — property-data가 조립·전달 (`PredictionFeatureRepository`) | 직접 조회 — `ai_read` 읽기 전용 뷰 계약 |
| 캐시 | property-data 소유 (`PredictionCacheRepository`) | 자체 소유 |
| 마이그레이션 | 없음 | Alembic (`ai` 스키마 한정) |
| LLM provider 호출 | 없음 | ai-service만 |
| 장애 시 | 예측만 degrade (`PredictionStatus`), 지도 무영향 | 챗봇만 다운, 지도 무영향 |
| 배포 | 독립 컨테이너, 변경 드묾 (운영 표면) | 독립 컨테이너, 변경 잦음 (실험 표면) |

통합하지 않는 이유: 예측은 지도 상세에 붙은 운영 기능이고 챗봇은 실험 속도가
빠른 표면이다. 한 컨테이너로 합치면 챗봇 변경마다 예측이 재배포되고, 안정된
수치 스택이 무겁고 자주 바뀌는 LLM/vector 의존성을 상속한다.

### 8.4 호출 방향 매트릭스

```
property-data(api) → ml-inference     ✅ 동기 + 캐시 + degrade 허용
ai-service         → property-data        ✅ 사실은 ai_read 뷰, 계산은 api API
ai-service         → ml-inference     ❌ 직접 호출 금지 — 피처 조립은 property-data 책임.
                                         챗봇의 예측 도구는 api의 예측 API 경유
                                         (캐시·피처 정합성·degrade 정책 재사용)
ml-inference       → (모든 것)        ❌ 완전 말단. DB도 타 서비스도 모른다
property-data          → ai-service       ❌ property-data 도메인 로직의 AI 의존 금지
                                         (지도/거래는 AI 없이 동작 — 가드레일)
                                         예외: api의 BFF 중계 모자 — web의 챗
                                         요청을 ai-service로 라우팅·SSE 프록시하는
                                         통로는 허용 (D22). 도메인 의존이 아니다
user-service       ↔ property-data        참조는 id뿐 (user_id, complex_id). 조인 없음

[비동기 — D21 도입 트리거 이후 (outbox 경유, at-least-once)]
property-data → event → ai-service        수집/정규화 완료 → 재임베딩 트리거
property-data → event → user-service/ops  상태 변화 → 알림·후속 반응
```

챗봇(RAG)의 도구는 두 종류로 고정된다: 사실 조회 = `ai_read` 뷰,
계산된 값(예측 등) = api API. 이 규칙이 피처 조립 로직의 복제를 막는다.

### 8.5 지금 지키는 규율 (Stage 1에서 사는 것)

- D14 boundary test: feature 패키지 간 직접 import 금지를 rtms-ingest-core의
  `RtmsIngestCoreBoundary` 선례와 같은 방식으로 강제한다.
- 어댑터 안에서 순수 파싱과 판단 로직을 클래스 수준에서 분리 유지 (§3.6 geo).
- 도메인 정책을 공용 유틸로 끌어올리지 않는다 (D13). "여러 feature가 같은
  판단을 원한다"는 신호가 오면 라이브러리가 아니라 domain 소유로 내린다.

## 9. 배포 아키텍처 (D20)

### 9.1 선택: ECS on EC2 + RDS

검토안 — A(EC2+compose), B(ECS on Fargate), B′(ECS on EC2), C(EC2 올인원) —
중 B′ 채택. 근거:

- 서비스별 태스크 정의 + 독립 배포(`aws ecs update-service`)가 §8.1 서비스
  지도의 배포 표현이 된다. 구조가 아키텍처를 표현한다는 원칙의 배포판.
- 관리형 수명주기: 태스크별 로그(awslogs→CloudWatch), 헬스체크 기반 자동
  재기동, 롤링 배포를 직접 만들지 않고 얻는다.
- batch 트리거가 EventBridge Scheduler → ECS RunTask 직결 (D12 원문 유효).
- Fargate 대비: 컨트롤 플레인 무료 + EC2 고정비라 컨테이너 수가 늘어도
  (ml-inference, ai-service…) 비용이 서비스당 증가하지 않는다.
- 진화 경로: 스케일아웃 필요 시 같은 태스크 정의로 launch type만 Fargate로
  전환 가능 (B′→B).

### 9.2 구성

```
EC2 t4g.small (arm64, ECS 에이전트)      RDS db.t4g.micro (PostGIS)
├─ ECS service: nginx (TLS, 경로 라우팅)  ├─ home_search
├─ ECS service: api                   │   public(Spring) / ai_read / ai(미래)
├─ ECS service: ml-inference              └─ coordinate_source
├─ ECS service: redis
├─ (미래) ECS service: ai-service         S3 + CloudFront: apps/web 정적 배포
└─ ECS task:    batch-app                 ECR: 이미지 저장 (git SHA + latest 태그)
                ← EventBridge Scheduler   SSM Parameter Store: 시크릿 주입
```

- 호스트 승급 예정: ai-service 진입 시 t4g.medium(4GB). ECS 에이전트 +
  JVM + Python 2개 + 임베딩 메모리를 2GB로는 감당하지 못한다.
- 예상 고정비: ~$40/mo (RDS ~$21 + EC2 ~$17 + 기타). medium 승급 시 ~$55/mo.

### 9.3 원칙

- 태스크 정의·스케줄·IaC는 전부 레포 안에서 버전 관리한다. 호스트나 콘솔에만
  존재하는 설정 금지 (레거시 crontab 교훈, §5.6).
- 로컬 개발은 compose 유지 — 의도적 비대칭. compose는 빠른 반복용,
  태스크 정의는 운영용이며 서로 대체하지 않는다.
- 운영 모니터링은 CloudWatch + Slack 알림(기존 notifier)으로 시작.
  grafana/loki/alloy 스택은 로컬 전용으로 유지. Container Insights는 비용
  확인 후 선택 도입.
- CI/CD는 레거시 파이프라인 계승 + 교정 2개: 테스트(`test`+`persistenceTest`)
  통과를 이미지 푸시의 전제로 (레거시의 `-x test` 금지), `:latest` 단독 대신
  git SHA 태그 병기로 롤백 가능하게. 배포는 변경된 서비스만
  `update-service --force-new-deployment`.
- `home_search` Flyway migrate 실행 주체는 api (§5.4). batch-app은
  validate만 한다. ai-service는 `home_search` Flyway를 실행하지 않고, 자기
  `ai` 스키마는 Alembic으로 소유한다 (D25).

## 10. MSA/Kafka/DB 설계 선행 게이트

새 스키마·새 서비스·이벤트가 걸린 구현(ai-service, user-service, Kafka producer/
consumer)에 착수하기 전, 다음 산출물을 별도 문서에서 확정해야 한다 (D24).
§6의 분할 1차·2차와 batch 전환은 현행 DB 그대로이므로 이 게이트의 대상이 아니다 —
분할 2차의 `batch` 스키마(`BATCH_*` 메타데이터) 신설은 도메인 데이터가 아닌
인프라 배관이므로 게이트 대상에 포함되지 않는다.

### 10.1 DB 설계 산출물 (확정 위치: docs/DATA_STORAGE.md)

- home_search schema/table ERD
- coordinate_source DB 책임과 source-data 운영 책임
- ai schema / vector index 소유권
- ai_read read-only view 계약과 권한
- users schema future ownership
- 서비스별 DB 권한표
- schema별 migration owner (D25)
- raw-first ingest / dedupe / failed match queryability 불변식
- event_outbox / processed_event 저장 모델 (D23 — 브로커 도입 전 선행 가능)
- 백업/복구/rollback 기준

### 10.2 Kafka 설계 산출물 (D21 도입 트리거 도달 시)

- 운영 런타임 결정: Amazon MSK vs EC2 self-host(KRaft) vs 호환 대안 —
  비용 실측 근거 필수 (MSK 최소 ~$70+/mo가 §9.2 고정비를 초과함을 전제로 판단)
- 로컬: Docker Compose Kafka (KRaft mode)
- topic naming/versioning
- event envelope
- partition key
- producer outbox 정책 (D23)
- consumer idempotency 정책 (D23)
- retry / DLQ 정책
- schema evolution 정책

### 10.3 서비스 통신 산출물

- 동기 HTTP/SSE 호출 매트릭스 — §8.4를 단일 소스로 유지·확장
- Kafka event flow (§8.4 비동기 블록 상세화)
- 금지 호출 방향 (§8.4)
- timeout / retry 상한 / degrade 정책 (동기)
- 내부 인증/인가 정책 (서비스 간 호출 신원 — user 도메인 진입 시 D16과 연계)

### 10.4 배포 전 게이트 (D26)

- 서비스별 Docker build
- compose smoke
- Flyway baseline
- API contract check (`docs/API_CONTRACT.md`)
- 데이터 안전: raw-first / dedupe / failed-match queryability check
- 서비스 분리: ml 장애 시 map/trade degrade, ai 장애 시 map/trade 무영향 check
- Kafka smoke + outbox publish + consumer idempotency + DLQ routing
  (첫 producer 도입 시부터 활성)
- rollback: git SHA 이미지 태그 + migration rollback/forward-fix 기준
