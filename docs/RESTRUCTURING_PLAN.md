# Backend Restructuring Plan (초안)

백엔드(apps/api)를 Gradle 멀티모듈(`core` / `api-app` / `batch-app`)로 재구조화하고,
RTMS daily refresh 스케줄링을 Spring Batch 기반 run-and-exit 배치로 전환하기 위한 설계 문서.

- 상태: 초안 (대화 기반 확정 사항 기록, 세부는 후속 섹션에서 갱신)
- 작성일: 2026-07-06
- 전제: 재작성(rewrite)이 아니라 검증된 코어를 보존하는 구조 이동 + 껍데기 교체(strangler)

## 1. 배경과 목표

- 현재 `apps/api`는 단일 Spring Boot 프로젝트이며, RTMS daily refresh는
  `infrastructure/scheduling/rtms`의 `@Scheduled` 스케줄러가 상주 프로세스 안에서 실행한다.
- 목표:
  1. 배치를 상주 프로세스에서 분리해 run-and-exit 컨테이너로 실행한다.
  2. 스케줄링 골격을 Spring Batch 표준(restart, 파티션 단위 실패 격리, 메타데이터)으로 교체한다.
  3. 공유 코어(domain/application/persistence/adapter)는 한 벌로 유지하고
     배포 단위만 둘(api-app, batch-app)로 나눈다.
- 비목표:
  - `application/ingest/**` 파이프라인(매칭·정규화·dedupe) 재작성 금지. 한 줄도 바꾸지 않는 것이 기본값.
  - 신규 레포/신규 프로젝트 시작 금지. 기존 테스트 자산(test 350+, persistenceTest 199)을 안전망으로 사용.
  - MSA 서비스 분리 아님. batch와 api는 같은 bounded context(trade ingest/read)의 두 실행 모드다.

## 2. 확정 결정 사항 (Decision Log)

| # | 결정 | 내용 |
|---|---|---|
| D1 | 모듈 구성 (재개정) | `core` + boot jar 2개(`api-app`, `batch-app`). 원칙: **모듈 경계는 서비스 경계(D15)를 비춘다** — core는 home-data-service의 몸체이므로 한 덩어리가 정확한 반영이다. core-trade/core-map 분할안은 검토 후 폐기: 쓰기 파이프라인이 region 동기화·coordinate preflight·complex metadata를 가로질러(daily 스케줄러의 region import, 매칭의 metadata 4타입) 상호 의존 금지가 성립하지 않고, 강행 시 core-shared가 부활한다. `core-shared`·`core-schema`도 두지 않는다(Flyway는 core에) |
| D2 | 모듈 이름 | `core` / `api-app` / `batch-app`. user 스코프 진입 시 `core-user` 추가 (D16) |
| D3 | 패키지명 불변 | 자바 패키지(`com.home.*`)는 그대로 두고 소스루트만 이동. import 변경 0이 원칙 |
| D4 | 신규 libs 생성 보류 | 지금은 `libs/rtms-ingest-core`만 유지. 신규 라이브러리는 §4 승격 조건 충족 시에만 |
| D5 | 코어 보존 | ingest 코어는 자산. 교체 대상은 orchestration 껍데기(`infrastructure/scheduling/rtms` 약 1,400줄)뿐 |
| D6 | 전환 방식 | strangler: 신규 Spring Batch job과 기존 `@Scheduled` 경로 병행 검증 후 구 경로 삭제 |
| D7 | enrichment 스케줄러 | `ComplexMetadataEnrichmentScheduler`는 당분간 api-app 잔류. batch-app 후속 job 후보로만 표시 |
| D8 | `rtms_ingest_run` 유지 | 도메인 운영 증거 테이블. `BATCH_*` 메타데이터로 대체하지 않음 |
| D9 | JobParameters 규약 | daily identifying 파라미터는 `runDate`(yyyy-MM-dd) 하나를 기본값으로 둔다. 같은 날 재실행은 동일한 resolved workset을 대상으로 한다는 검증이 전제이며, 파티션 restart를 도입하기 전에는 workset snapshot 또는 plan drift 검증 방식을 확정한다. `baseDealYmd`는 runDate에서 파생 |
| D10 | job 카탈로그 | batch-app은 multi-job 그릇. 시작은 `rtmsDailyRefreshJob` + `rtmsBackfillJob` 2개. job 1개 = 책임 1개, 이질적 스텝 체이닝 금지 |
| D11 | 파티션/병렬화 보류 | 첫 batch 전환은 기존 월 단위 refresh 의미론을 감싸는 순차 실행으로 시작할 수 있다. Partitioned Step은 restart 단위와 plan drift 처리 방식을 확정한 뒤 도입하며, 병렬화 승격 기준(§5.5)은 실측 이후로 의도적 보류 |
| D12 | 스케줄 트리거 | AWS EventBridge Scheduler → 컨테이너 실행(ECS RunTask). 전제: 운영 런타임이 AWS(DB 접근 가능). 스케줄 정의는 IaC로 레포에서 버전 관리 |
| D13 | 정책 공유 금지 | MSA 전제에서도 도메인 정책/판단은 라이브러리로 서비스 간 공유하지 않는다. 공유 허용은 wire client·프리미티브·기술 섀시뿐 (§3.5) |
| D14 | feature 경계 규율 | `application/**` feature 패키지 간 직접 import 금지. 단일 core 내부의 조망성 경계이며 boundary test로 강제 (기지 baseline 1건: coordinate→complex) |
| D15 | 서비스/DB 경계 | 서비스 경계는 데이터 소유권으로 긋는다: `home-data`(trade+map 한 몸, DB 하나) / `user`(미래) / `ai`(미래, later-scope). trade↔map 간 DB 분리는 기본 로드맵에서 제외 — 프로젝션 동기화 비용을 정당화할 근거가 확인되기 전에는 하지 않는다 |
| D16 | user 도메인 탄생 수칙 | user/JWT/OAuth가 스코프에 들어오는 날: `core-user` 모듈 신설 + 전용 `users` 스키마 + 전용 Flyway location + 토큰 발급(user 소유)/검증(기술 섀시, api-app·BFF) 분리 + 맵 공개 표면은 무인증 유지 |
| D17 | 내부 경계 수단 | 단일 core 내부의 조망성은 feature 패키지 + D14 boundary test가 담당한다. 워크로드 격리(쓰기/읽기)는 모듈 분할이 아니라 배포 모드 분리(batch-app)가 담당한다. 기지 예외: `coordinate→complex` 1건은 boundary test의 명시적 baseline으로 기록 후 점진 해소 |
| D18 | AI 구성요소 분리 | ml-inference(apps/ml, 가격 예측)와 ai-service(챗봇, 미래)는 통합하지 않는다. ml-inference는 상태 없는 추론 함수(DB 무접속, 피처·캐시는 home-data 소유)이고 ai-service는 상태를 소유하는 서비스(`ai` 스키마 + vector). 운영 표면(예측)을 실험 속도가 빠른 표면(챗봇)의 배포에 묶지 않는다. 호출 방향은 §8.4 매트릭스를 따른다 |
| D19 | 챗봇 이식 청사진 | ai-service의 참조 구현은 `/Users/gwongwangjae/kosa-team5/server`(읽기 전용). 이식 설계는 `docs/AI_SERVICE_PLAN.md` — real_estate 부분은 이식하지 않고(Spring이 대체), 챗봇 파이프라인은 계승하되 feature dao의 직접 SQL을 `ai_read` 뷰 조회로 치환하는 것이 핵심 작업. 지위는 여전히 later-scope |
| D20 | 배포 아키텍처 | ECS on EC2 + RDS (§9). 운영 런타임 AWS 확정(D12 전제 충족). 서비스별 태스크 정의 = MSA 서비스 지도의 배포 표현. 로컬은 compose 유지(의도적 비대칭, 태스크 정의는 레포 내 IaC로 버전 관리). ml-inference/ai-service 분리(D18)에 재검토 조건 부여: ai-service 운영 6개월 후 Python 2서비스 운영 부담이 실증되고 챗봇 의존성이 안정화되면 합류 재론 |

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
| ④ service contract | 서비스 간 내부 API DTO | trade-service 내부 조회 계약 | 서비스 분리 + 상호 호출 발생 시 |

원칙 (D13): 비즈니스 정책·판단(매칭 정책, dedupe, marker-safe 규칙)은 어떤
카테고리로도 공유하지 않는다. 정책을 jar로 공유하는 순간 규칙 변경마다 전
서비스 락스텝 배포가 필요해지는 분산 모놀리스가 된다. 정책은 소유 서비스
안에만 살고, 다른 서비스는 API(또는 뷰 계약)로 결과만 소비한다.

현실 제약: batch-app은 같은 빌드라 core가 섀시 역할을 하고(퍼블리시 불필요),
FastAPI ai-service는 Python이라 Java 라이브러리를 소비하지 못한다. 따라서
③④의 실제 트리거는 trade/map의 Spring 서비스 분리 시점이다.

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
- 사용처: batch-app job 결과 알림(전 job 공통), api-app 운영 알림(향후),
  분리된 각 서비스의 공통 알림 경로.

## 4. 목표 모듈/패키지 구조

```
home-search/
├─ libs/
│   └─ rtms-ingest-core/                  # 유지. 프리미티브 추가 승격도 여기로
├─ apps/
│   ├─ api/                               # Gradle 멀티모듈 루트
│   │   ├─ core/                          # home-data-service의 몸체 (boot jar 아님)
│   │   │     com.home.domain.**
│   │   │     com.home.application.**     # feature 패키지 = 내부 경계 (D14)
│   │   │     com.home.infrastructure.persistence/external/cache/observability.**
│   │   │     src/main/resources/db/migration/**       # Flyway SQL
│   │   ├─ api-app/                       # boot jar ① 상주 웹 서버 = 서빙 모드
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
api-app  → core        batch-app → core        core → libs/rtms-ingest-core
(미래) core-user는 core와 상호 의존 금지 — 서비스 경계이므로 컴파일러로 강제
```

경계 수단의 역할 분담 (D17):

- 모듈 경계 = 서비스 경계 (D15). core가 한 덩어리인 것은 trade+map이
  한 서비스라는 사실의 정확한 반영이다. core-trade/core-map 분할은 폐기 —
  쓰기 파이프라인(daily 배치)이 region 동기화·coordinate preflight·complex
  metadata를 가로지르므로 워크로드 축과 도메인 축이 평행하지 않다.
- 워크로드 격리(쓰기/읽기)는 배포 모드(api-app vs batch-app)가 담당한다.
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
- job 트리거는 "컨테이너 실행 + 파라미터"로 통일한다. api-app에 job 트리거용
  HTTP 엔드포인트를 만들지 않는다 (모듈 분리 훼손).

### 5.4 실행/배포 모드

| 구분 | 1단계 (api-app 내장) | 2단계 (batch-app run-and-exit) |
|---|---|---|
| 트리거 | 얇은 `@Scheduled` → `JobLauncher.run` | EventBridge Scheduler → ECS RunTask (D12). 로컬은 `docker compose run --rm batch-app` 수동 실행 |
| `spring.batch.job.enabled` | `false` (부팅 시 자동 실행 방지) | `true` (부팅 = 실행이 의도) |
| `web-application-type` | `servlet` | `none` |
| job 선택 | 코드에서 지정 | `SPRING_BATCH_JOB_NAME` env / 커맨드라인 |
| Flyway | api-app이 migrate | batch-app은 validate만. migrate는 api-app(또는 추후 독립 마이그레이션 잡) |

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

1. 설계 md 확정          이 문서. 후속 대화로 §5 미확정 항목 갱신

2. [분할 1차]            core + api-app 모듈 2개로 분리. batch-app은 만들지 않음
                         순수 파일 이동, 동작 변화 0 — Gradle 멀티모듈화의
                         기계적 비용(빌드/harness/compose 참조)을 최소 결정으로 통과
                         scheduling 패키지는 api-app에 그대로 잔류
                         완료 조건: test + persistenceTest 전부 GREEN
                         부수 작업: harness/compose의 apps/api 빌드 태스크 참조 갱신
                         (:api-app:bootRun 등)

2.6 [경계 테스트]        D14 boundary test 도입 (feature 간 import 금지,
                         coordinate→complex 1건은 명시적 baseline)
                         분할 1차와 같은 PR 또는 직후 소형 PR

3. [분할 2차]            batch-app 신설 (core 의존) + rtmsDailyRefreshJob
                         신규 작성 (§5). 초기 구현은 순차 실행 가능,
                         Partitioned Step은 restart/drift 정책 확정 후 도입
                         기존 @Scheduled 경로는 api-app에 살아 있는 채 병행 검증
                         검증: 신규 job 수동 실행 → rtms_ingest_run 결과를
                         기존 경로 실행분과 비교 (dedupe 불변식으로 이중 실행 안전)

4. 구 경로 삭제          병행 검증 통과 후 api-app에서 scheduling/rtms 제거
                         run-and-exit 컨테이너 배포 전환 (§5.4의 2단계 모드)
```

각 단계는 독립 PR로 진행하고, 구조 이동(2)과 신규 작성(3)을 한 PR에 섞지 않는다.

## 7. 관련 확정 원칙 (이 계획의 상위 제약)

- single-writer의 "writer"는 프로세스가 아니라 소유권 경계(코드베이스 + Flyway)다.
  api-app과 batch-app은 같은 경계의 두 프로세스이므로 원칙 위반이 아니다.
- 스키마 주인은 하나: `home_search`의 Flyway 히스토리는 core 한 곳에서만 관리한다.
- FastAPI AI 서비스(챗봇/RAG)는 later-scope이며 구현은 이 문서 범위 밖.
  설계 청사진은 `docs/AI_SERVICE_PLAN.md`에 작성됨 (D19) — 참조 구현 이식 매핑,
  `ai_read` 뷰 계약, `ai` 스키마, 단계적 이식 순서 포함. 착수는 re-scope 결정 이후.
- 공개 API URL/응답 형태는 이 재구조화에서 변경하지 않는다 (`docs/API_CONTRACT.md` 준수).

## 8. MSA 진화 로드맵

이 재구조화는 MSA의 사전 단계다. 서비스 경계는 "따로 떠 있는가"가 아니라
데이터 소유권으로 긋고, 분리는 실측 병목이 확인된 뒤에만 진행한다.

### 8.1 서비스 지도 (데이터 소유권 기준, D15)

경계는 두 종류이며 혼동하지 않는다. **모듈 경계**(core-trade/core-map)는 같은
데이터 위의 관심사 분리(쓰기 파이프라인 vs 읽기 표면)이고, **서비스·DB 경계**는
데이터 소유권이다. trade와 map은 데이터가 한 몸이므로(`complex_id` 불변식,
read의 교차 조인, 마커 가격) 서로 다른 서비스가 되지 않는다.

| 서비스 | 구성 | 소유 데이터 | 상태 |
|---|---|---|---|
| home-data-service | core (몸체) + api-app(서빙 모드) + batch-app(배치 모드) | `home_search` DB: raw ingest, normalized trade, 매칭 증거, region/complex/좌표, `rtms_ingest_run` | 현재 구축 중 |
| user-service | `core-user` (미래) | `users` 스키마 → 전용 DB (D16) | 미래, 스코프 진입 시 |
| ai-service | FastAPI (미래) | `ai` 스키마 + vector index. 사실은 `ai_read` 뷰로 구독 | later-scope, 청사진: `docs/AI_SERVICE_PLAN.md` (D19) |
| ml-inference | apps/ml FastAPI (D18) | **없음** — 무상태 추론 함수. 모델 아티팩트 파일만. 피처·예측 캐시는 home-data 소유 | 현존 운영 |
| (선례) coordinate_source | 독립 조회 DB | PNU 좌표 | 이미 분리됨 |

api-app의 web 레이어는 서비스가 늘어나는 시점에 api-gateway/BFF 역할
(라우팅 + 토큰 검증)을 겸하는 후보다.

run-and-exit batch-app은 "쓰기/읽기 워크로드 격리"를 DB 분리 없이 배포 모드
분리로 달성한다. 읽기 스케일이 필요해지면 리드 레플리카가 다음 수단이며,
이 역시 소유권 분리가 아니다.

### 8.2 진화 단계

```
Stage 0  현재: 단일 boot 프로젝트 + libs/rtms-ingest-core + apps/ml(ml-inference, D18)
Stage 1  멀티모듈 (§6-2): core / api-app / batch-app + D14 boundary test
Stage 2  batch run-and-exit 배포 분리 (§6-3,4) + AWS 운영 (D12)
Stage 3  [스코프 진입 이벤트, 순서 무관·독립]
         - user-service: core-user + users 스키마 + JWT/OAuth (D16)
         - ai-service: FastAPI + ai_read 뷰 계약 + ai 스키마 (re-scope 결정 필요)
Stage 4  물리 분리: user/ai 스키마를 전용 DB로 승격, 서비스 독립 배포.
         home-data 내부의 trade↔map DB 분리는 로드맵에 없다 (D15) —
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
| 피처/사실 획득 | 받는다 — home-data가 조립·전달 (`PredictionFeatureRepository`) | 직접 조회 — `ai_read` 읽기 전용 뷰 계약 |
| 캐시 | home-data 소유 (`PredictionCacheRepository`) | 자체 소유 |
| 마이그레이션 | 없음 | Alembic (`ai` 스키마 한정) |
| LLM provider 호출 | 없음 | ai-service만 |
| 장애 시 | 예측만 degrade (`PredictionStatus`), 지도 무영향 | 챗봇만 다운, 지도 무영향 |
| 배포 | 독립 컨테이너, 변경 드묾 (운영 표면) | 독립 컨테이너, 변경 잦음 (실험 표면) |

통합하지 않는 이유: 예측은 지도 상세에 붙은 운영 기능이고 챗봇은 실험 속도가
빠른 표면이다. 한 컨테이너로 합치면 챗봇 변경마다 예측이 재배포되고, 안정된
수치 스택이 무겁고 자주 바뀌는 LLM/vector 의존성을 상속한다.

### 8.4 호출 방향 매트릭스

```
home-data(api-app) → ml-inference     ✅ 동기 + 캐시 + degrade 허용
ai-service         → home-data        ✅ 사실은 ai_read 뷰, 계산은 api-app API
ai-service         → ml-inference     ❌ 직접 호출 금지 — 피처 조립은 home-data 책임.
                                         챗봇의 예측 도구는 api-app의 예측 API 경유
                                         (캐시·피처 정합성·degrade 정책 재사용)
ml-inference       → (모든 것)        ❌ 완전 말단. DB도 타 서비스도 모른다
home-data          → ai-service       ❌ 지도/거래는 AI 없이 동작 (가드레일)
user-service       ↔ home-data        참조는 id뿐 (user_id, complex_id). 조인 없음
```

챗봇(RAG)의 도구는 두 종류로 고정된다: 사실 조회 = `ai_read` 뷰,
계산된 값(예측 등) = api-app API. 이 규칙이 피처 조립 로직의 복제를 막는다.

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
├─ ECS service: api-app                   │   public(Spring) / ai_read / ai(미래)
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
- Flyway migrate 실행 주체는 api-app (§5.4). batch-app·ai-service는 validate만.
