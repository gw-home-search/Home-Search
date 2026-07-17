# Slice 3 부동산 데이터 준비 보고서

기준일: 2026-07-17
판정: `Pass` — 전국 17개 시도 커버리지와 단지·거래 정합성을 확인하여 단지 식별,
최근 실거래, 기간별 가격 추이·거래량 질문을 활성화할 수 있다. 좌표 결측 단지와
격리된 match 실패는 질문별 limitation으로 유지한다.

## 감사 대상과 실행 원칙

- 대상: 기존 `home_search` 데이터베이스의 `region`, `parcel`, `complex`, `trade`,
  `raw_trade_ingest`, `trade_match_evidence`, `trade_source_key_registry`,
  `rtms_ingest_run`
- 최종 감사 시각: 2026-07-17 00:43 UTC
- 데이터 변경: 승인된 `rtmsBackfillJob`으로 세종 `36110`의 2012-01~2026-07
  데이터를 기존 volume에 additive 방식으로 적재했다.
- Docker volume 삭제·초기화: 없음
- 기존 공개 부동산 API URL·요청·응답 계약 변경: 없음
- snapshot checksum: 해당 없음. 이번 Slice는 기존 운영 DB의 시점 감사이며 별도 불변
  snapshot을 생성하지 않았다. 실행 범위는 아래 correlation ID로 추적한다.

## 세종 backfill 실행 결과

전용 Compose는 `apps/property-data/.env`를 project directory 기준으로 읽고 property
runtime role과 coordinate reader role만 사용한다. API key와 DB password는 Compose
config나 애플리케이션 로그에 출력하지 않았다.

| 범위 | correlation ID | 결과 |
|---|---|---|
| 2026-07 smoke | `fa704196-c7d4-4924-b92e-af5aa076bc30` | `COMPLETED` |
| 2020-01~2026-06 | `f8972380-2559-45ec-8c89-f9fcb4e22bee` | 77개월 `COMPLETED`, 2021-08 API timeout 1회 |
| 2021-08 재시도 | `b975dc54-686d-4110-a9dc-58e565ed5379` | `COMPLETED` |
| 2012-01~2019-12 | `5559a0ae-4909-4169-9ecb-a39d937a4239` | 96개월 모두 `COMPLETED` |

최종적으로 2012-01~2026-07의 서로 다른 175개월이 모두 `COMPLETED`됐다. 성공한 월의
집계는 다음과 같다.

| 항목 | 행 수 |
|---|---:|
| RTMS read/raw saved | 57,012 |
| `NORMALIZED` | 53,379 |
| `DUPLICATE` | 1,268 |
| `CANCELED` | 2,278 |
| `MATCH_FAILED` | 87 |
| `PARSE_FAILED` | 0 |

87건의 match 실패는 모두 `산울마을5단지`, `apt_seq=36110-610`, 원본 지번 `가-`로
인해 PNU를 만들 수 없는 동일 패턴이다. `PNU_UNAVAILABLE`, `invalid jibun` 근거와 함께
삭제하지 않고 조회 가능한 상태로 보존했다. 해당 행은 2024-04~2026-05 거래이므로 이
단지의 해당 기간 수치에는 데이터 누락 limitation을 표시해야 한다.

## 현재 행 수와 신선도

| 항목 | 행 수 또는 값 |
|---|---:|
| region | 5,337 |
| parcel | 43,721 |
| complex | 44,200 |
| trade 전체 | 7,580,522 |
| trade 활성 행 | 7,543,829 |
| trade 삭제 표시 행 | 36,693 |
| raw_trade_ingest | 7,956,069 |
| trade_match_evidence | 7,603,585 |
| 최초 거래일 | 2012-01-01 |
| 최신 거래일 | 2026-07-16 |
| 감사일 이후 미래 거래 | 0 |

전체 raw 상태는 `NORMALIZED` 7,580,526건, `DUPLICATE` 190,511건,
`CANCELED` 169,325건, `MATCH_FAILED` 15,707건이다. 전체 match evidence는
`MATCHED` 7,587,878건, `PNU_CONFLICT` 3,917건, `PNU_UNAVAILABLE` 11,604건,
`UNMATCHED` 186건이다.

## 식별자와 거래 정합성

- 세종 `(source, source_key)` 중복 trade group: 0건
- 세종 trade와 complex 사이 `complex_pk` 불일치: 0건
- 세종 trade와 complex 사이 `apt_seq` 불일치: 0건
- 세종 필수 complex 식별자 결측: 0건
- 세종 미래 거래와 0 이하 거래금액: 각각 0건
- 신규 세종 `NORMALIZED` raw 53,379건과 trade 53,379건은 1:1로 일치한다.
- FK가 보장하는 운영 관계는 기존과 동일하게 `trade.complex_id -> complex.id`이다.
- `complex_id`, `complex_pk`, `apt_seq`, `source`, `source_key`의 의미나 저장값을
  변경하지 않았다.

기존 `NORMALIZED` raw와 trade 사이 4건 차이는 전수 join 대신 `raw_ingest_id` 범위를
100만→10만→1만→1천 단위로 좁혀 원인을 확인했다. 다음 raw는 `NORMALIZED`지만
registry와 trade가 없으며, 모두 세종 backfill 이전인 2026-06-12에 처리된 기존 행이다.

| raw ID | LAWD | 거래월 | evidence |
|---:|---|---|---|
| 4,050,473 | 41171 | 201201 | 없음 |
| 4,050,474 | 41192 | 201201 | 없음 |
| 7,876,474 | 52113 | 201410 | `MATCHED`, trade 없음 |
| 7,876,475 | 52111 | 201407 | 없음 |

따라서 이 차이는 중복 trade가 아니라 기존 raw 상태 불일치다. 데이터 의미를 임의로
재해석하지 않고 별도 품질 이슈로 남기며, AI read model은 실제 `trade`만 읽는다.

## 좌표 품질

| 검사 | 결과 |
|---|---:|
| parcel 좌표 양쪽 모두 결측 | 577 |
| parcel 위도/경도 한쪽만 결측 | 0 |
| 대한민국 표시 범위 밖 parcel 좌표 | 0 |
| canonical complex 좌표 결측 | 586 |
| 대한민국 표시 범위 밖 canonical complex 좌표 | 0 |
| marker-safe complex | 43,614 / 44,200 |
| 세종 marker-safe complex | 222 / 222 |

canonical complex 좌표는 기존 API와 동일하게 complex display coordinate를 우선하고
parcel 좌표를 대체값으로 사용하는 기준으로 검사했다. 좌표가 없는 기존 586개 단지는
지도 거리·주변 시설 질문에 사용할 수 없다.

## 전국 시도 커버리지

| 시도 | parcel | complex | 결과 |
|---|---:|---:|---|
| 서울 | 9,108 | 9,193 | 확인 |
| 부산 | 4,847 | 4,873 | 확인 |
| 대구 | 2,085 | 2,097 | 확인 |
| 인천 | 2,283 | 2,327 | 확인 |
| 광주 | 1,190 | 1,203 | 확인 |
| 대전 | 979 | 996 | 확인 |
| 울산 | 1,611 | 1,616 | 확인 |
| 세종 | 222 | 222 | **복구·확인** |
| 경기 | 7,469 | 7,653 | 확인 |
| 충북 | 1,189 | 1,201 | 확인 |
| 충남 | 1,368 | 1,381 | 확인 |
| 전남 | 1,275 | 1,294 | 확인 |
| 경북 | 2,815 | 2,826 | 확인 |
| 경남 | 3,621 | 3,638 | 확인 |
| 제주 | 1,076 | 1,078 | 확인 |
| 강원 | 1,239 | 1,255 | 확인 |
| 전북 | 1,344 | 1,347 | 확인 |

세종 누락 원인은 기본 LAWD reader가 `region_type = 'si-gun-gu'`만 조회해 시도 직속
읍면동 구조의 `36110`을 만들지 못한 것이었다. reader가 시도 직속 8자리 읍면동 코드의
앞 5자리를 합성하도록 보정했고, fresh migration 회귀 test와 실제 175개월 backfill로
복구를 검증했다.

## Capability 판정

| 질문 유형 | 상태 | 사유 |
|---|---|---|
| 단지 식별·단순 조회 | `supported` | 전국 17개 시도 단지와 식별자 정합성 확인 |
| 최근 실거래 | `supported` | 실제 trade와 source identity를 기준으로 제공 |
| 기간별 가격 추이·거래량 | `supported` | 2012-01~2026-07 범위와 단위·기간 제한을 표시 |
| 좌표 기반 거리 질문 | `partial` | 좌표 없는 기존 단지 586개 제외 필요 |
| 산울마을5단지 2024-04~2026-05 | `partial` | 원본 지번 오류로 격리된 거래 87건 표시 필요 |
| 교육·교통·의료·추천 | `unavailable` | 후속 공식 데이터 Slice 미진행 |

## TDD·검증 근거와 잔여 위험

- 최초 RED: fresh migration에서 LAWD reader 결과에 `36110`이 없었다.
- 최소 GREEN: 일반 시군구 코드는 유지하고 시도 직속 읍면동에서 `36110`을 합성했다.
- Compose RED/GREEN: 전용 backfill Compose 부재와 잘못된 bind mount를 회귀 test로
  고정한 뒤 property runtime/coordinate reader 최소 권한 실행 계약을 추가했다.
- packaged runtime RED/GREEN: 실제 JAR에서 `PlatformTransactionManager`가 없던 실패를
  재현하고 JDBC runtime starter를 추가했다.
- 실데이터 검증: 1개월 smoke 후 두 구간 backfill, timeout 월 단독 재시도, correlation
  기반 월별 상태와 DB 불변식 감사까지 수행했다.

잔여 위험은 좌표 없는 586개 단지, 공식 원본 지번 오류 87건, 기존 raw 상태 불일치
4건이다. 이 항목은 fact/citation 생성 시 limitation으로 노출하며, 영향을 받는 범위를
넘어 완전성을 주장하지 않는다.

## `ai_read` 게시 계약

- Flyway V9가 `ai_read.complex_fact`와 `ai_read.trade_fact`를 게시한다.
- `complex_fact`는 `complex_id`, `complex_pk`, `apt_seq`, 표시명, 지역, 주소,
  marker-safe 좌표와 데이터 갱신 시각만 공개한다.
- `trade_fact`는 삭제되지 않은 정규 거래만 공개하며 금액은
  `deal_amount_ten_thousand_krw`, 면적은 `exclusive_area_square_meters`로 단위를
  컬럼명에 고정한다.
- `home_search_ai_reader`는 `NOINHERIT` 로그인이고 두 view의 `SELECT`만 허용된다.
  `public`, `reference`, `batch` 원본 테이블 조회와 `ai_read` 쓰기는 거부된다.
- 신규 view에는 권한이 자동 전파되지 않으며 후속 migration에서 명시적으로
  allowlist에 추가해야 한다.

Fresh PostGIS에서 V1~V9 exact history, view 조회 허용, 원본 조회·schema 쓰기·타 DB
접속 거부를 검증했다. 기존 local DB에도 2026-07-17 03:37:56 UTC에 V9를 적용했다.
Flyway는 configured schema인 `ai_read`를 먼저 만들고 history 13번에
`SCHEMA|"ai_read"`, 14번에 checksum `-1992481700`인 V9 SQL 성공 이력을 남겼다.
볼륨 초기화나 데이터 재수집은 수행하지 않았다.
적용 완료 후 재실행할 이유가 없는 legacy upgrade wrapper와 preflight 예외 mode는
제거했고, backup과 이 보고서만 복구·감사 증거로 보존한다.

기존 local DB는 보존된 JDBC/DELETE audit history 때문에 일반 fresh-only
preflight 대상이 아니다. 2026-07-17에 history 수정 없이 V9만 추가하는
exact-fingerprint legacy upgrade를 승인했고, 적용 전 custom-format backup을
`.migration-backup/20260717T025752Z-v9-legacy-upgrade/home_search_before_v9.dump`에
생성했다. backup은 2,493,719,006 bytes, 717 archive entries이며 SHA-256은
`0b580c9c4bef1af3f8a921e62024486a7c781ec329f6a5cd1e3bf2efd240356b`다.

적용 후 `complex_fact` 44,200행은 parcel이 존재하는 complex 수와 같고,
`trade_fact` 7,543,829행은 `deleted_at IS NULL`인 trade 수와 같다. 거래 fact의 필수
식별자 결측, 미래 거래일, 0 이하 금액·면적은 모두 0건이다. AI reader는 두 view의
`SELECT`만 보유하며 원본 `complex`·`trade` 조회, `ai_read` schema 생성,
`home_search` TEMP, `postgres` 연결 권한이 모두 없고 상속 role membership도 0건이다.
