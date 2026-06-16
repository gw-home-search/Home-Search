# Runner Lifecycle 기준

이 문서는 `apps/api`의 `*Runner`와 `*Scheduler`를 삭제 대상, 유지 대상,
후속 분리 대상으로 구분하기 위한 운영 기준이다. `ApplicationRunner`는
스케줄러가 아니며, `0 rows` 또는 disabled 기본값만으로 dead code로 보지
않는다.

## 분류 규칙

- `live`: 현재 runtime path 또는 data/API invariant를 직접 지킨다.
- `live-capable`: 기본값은 꺼져 있어도 property gate로 안전하게 활성화할 수 있다.
- `maintenance`: 복구, reconciliation, partition 보장처럼 운영 안전성을 지킨다.
- `one-shot`: bounded migration, backfill, admin, manual ops 실행 경로다.
- `later-scope`: 현재 map/trade critical path 밖이지만 의도적으로 보존한다.
- `removal-candidate`: 별도 evidence와 폐쇄 결정 전까지 삭제하지 않는다.

## 현재 Runner

| Class | Lifecycle | Gate | Keep reason | Next action |
| --- | --- | --- | --- | --- |
| `RawIngestReconciliationRunner` | maintenance | `home.ingest.raw-reconcile.enabled`, default-on | raw-first 처리 중단 후 `RECEIVED` raw row를 정상화하는 복구 안전장치 | 보존 |
| `TradePartitionMaintenanceRunner` | maintenance | `home.trade.partition.maintenance.enabled`, default-on | normalized `trade`의 미래 연도 partition을 보장한다 | 보존 |
| `RtmsOneShotIngestApplicationRunner` | one-shot | `home.ingest.rtms.enabled`, runtime no-op when false | 수동 RTMS ingest entrypoint이며 daily refresh와 별개다 | 보존 |
| `RtmsOneShotTradeIngestRunner` | live-capable | `RtmsOneShotIngestApplicationRunner`에서 호출 | RTMS one-shot ingest 구현 runner | 보존 |
| `RtmsMonthlyRefreshRunner` | live-capable | daily refresh 또는 nationwide backfill에서 호출 | lookback/monthly refresh 공통 실행기 | 보존 |
| `RtmsDailyRefreshScheduler` | live-capable | `home.ingest.rtms.daily.enabled` | 운영 daily RTMS refresh scheduler | 보존 |
| `RtmsNationwideBackfillRunner` | removal-candidate | `home.ingest.rtms.mode=nationwide-backfill` 실행 시 사용 | 전국 백필 완료 여부와 `rtms_backfill_*` row count 확인 전 삭제 위험 | G2-B에서 폐쇄 판단 |
| `TradeMatchRematchRunner` | one-shot maintenance | `home.ingest.match-rematch.enabled` | failed/raw 재매칭 복구 경로 | 보존 |
| `ComplexCoordinateReadinessRunner` | live-capable | `home.coordinate.readiness.enabled` | 좌표 예외 처리와 표시좌표 projection 준비 | 보존 |
| `ComplexCoordinateReadinessScheduler` | live-capable | `home.coordinate.readiness.enabled` and `home.coordinate.readiness.scheduler.enabled`, default-on when readiness enabled | 좌표 readiness를 주기적으로 갱신한다 | 보존 |
| `ComplexMetadataEnrichmentRunner` | one-shot ops | `complex.metadata.enrich.enabled` | complex metadata 보정 실행 경로 | 보존 |
| `ComplexMetadataEnrichmentScheduler` | live-capable | `complex.metadata.enrich.scheduler.enabled` | complex metadata 보정 주기 실행 경로 | 보존 |
| `RegionUnitCntSyncApplicationRunner` | one-shot ops | `home.region.sync.one-shot.enabled` | region marker `unitCntSum` metadata 동기화 경로 | 보존 |
| `NaverNewsOneShotIngestRunner` | later-scope | `home.news.naver.enabled` or news pipeline gates | news ingest 구현 runner | news app split 때 이동 후보 |
| `NaverNewsOneShotIngestApplicationRunner` | later-scope | `home.news.naver.enabled && !home.news.pipeline.enabled` | news one-shot entrypoint | news app split 때 이동 후보 |
| `NaverNewsSignalPipelineApplicationRunner` | later-scope | `home.news.pipeline.enabled` | news signal pipeline entrypoint | news app split 때 이동 후보 |
| `NaverNewsDailyPipelineRunner` | later-scope | `home.news.pipeline.daily.enabled` | daily news pipeline 구현 runner | news app split 때 이동 후보 |
| `NaverNewsDailyPipelineScheduler` | later-scope | `home.news.pipeline.daily.enabled` | daily news pipeline scheduler | news app split 때 이동 후보 |
| `NewsRelevanceGateApplicationRunner` | later-scope | `home.news.relevance.enabled` unless full pipeline enabled | standalone news relevance stage | news app split 때 이동 후보 |
| `NewsSignalFeatureExtractionApplicationRunner` | later-scope | `home.news.signal.extraction.enabled` unless full pipeline enabled | standalone news feature extraction stage | news app split 때 이동 후보 |
| `NewsArticleObservationCleanupApplicationRunner` | later-scope | `home.news.observation.cleanup.enabled` | news observation retention cleanup stage | news app split 때 이동 후보 |
| `NewsSignalObsidianExportApplicationRunner` | later-scope | `home.news.obsidian.export.enabled` unless full pipeline enabled | news Obsidian export stage | news app split 때 이동 후보 |

## 중단 조건

- `maintenance` runner 삭제 또는 default gate 변경은 raw-first, dedupe,
  failed match queryability 검증 없이는 중단한다.
- `RtmsNationwideBackfillRunner` 삭제는 `rtms_backfill_job`,
  `rtms_backfill_chunk`, `rtms_backfill_chunk_run` row count와 재실행 필요성
  확인 후 별도 PR에서만 진행한다.
- `later-scope` news runner는 API app에서 바로 삭제하지 않고 `apps/news`
  분리 설계와 Flyway baseline 결정 뒤 이동한다.
- public API URL, response shape, DB migration, ingest 정책 변경은 이 문서의
  scope가 아니다.
