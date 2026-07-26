package com.home.application.news.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsClassificationPolicy;
import com.home.domain.news.MarketNewsExecutionState;
import com.home.domain.news.MarketNewsFailureKind;
import com.home.domain.news.MarketNewsRelationPolicy;
import com.home.domain.news.MarketNewsRelationType;
import com.home.domain.news.MarketNewsScopeType;
import com.home.domain.news.MarketNewsWorkUnitKind;
import com.home.domain.news.MarketNewsWorkUnitState;
import com.home.domain.news.NewsRejectionReason;
import com.home.infrastructure.external.news.NaverNewsItemNormalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class MarketNewsCollectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T09:30:00Z");
    private static final UUID EXECUTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174300");
    private static final UUID WORK_UNIT_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174301");
    private static final UUID SECOND_WORK_UNIT_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174307");

    @Test
    @DisplayName("같은 requestId의 terminal run은 provider 재호출 없이 기존 결과를 반환한다")
    void returnsTerminalExecutionIdempotently() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        NewsItemNormalizationGateway normalizer = mock(NewsItemNormalizationGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        MarketNewsCollectionResult existing = new MarketNewsCollectionResult(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174300"),
                MarketNewsExecutionState.COMPLETED,
                23,
                23,
                0,
                0);
        when(repository.findTerminalResult("BOOTSTRAP-20260724")).thenReturn(Optional.of(existing));

        MarketNewsCollectionResult result = new MarketNewsCollectionService(repository, provider, normalizer, cache)
                .collectGeneral("BOOTSTRAP-20260724", Instant.parse("2026-07-24T09:30:00Z"), 4000);

        assertThat(result).isEqualTo(existing);
        verifyNoInteractions(provider, normalizer, cache);
    }

    @Test
    @DisplayName("같은 requestId의 RUNNING execution은 기존 call budget과 완료 unit을 이어서 재개한다")
    void resumesRunningExecutionWithoutCreatingAnotherPlan() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        NewsItemNormalizationGateway normalizer = mock(NewsItemNormalizationGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        MarketNewsCollectionExecution resumable = new MarketNewsCollectionExecution(
                EXECUTION_ID,
                "NEWS-RECOVERY-1",
                "GENERAL",
                "NEWS_V2",
                NOW,
                NOW.minusSeconds(2 * 60 * 60),
                4000,
                3999,
                2,
                1,
                0,
                0,
                0,
                List.of(resumedNationalUnit()));
        when(repository.findResumableExecution("NEWS-RECOVERY-1")).thenReturn(Optional.of(resumable));
        when(provider.search(any())).thenReturn(new NewsProviderPage(0, 201, 0, List.of()));
        when(repository.publishEligibleScopes(EXECUTION_ID, NOW)).thenReturn(List.of());

        MarketNewsCollectionResult result =
                service(repository, provider, normalizer, cache).collectGeneral("NEWS-RECOVERY-1", NOW, 4000);

        assertThat(result.state()).isEqualTo(MarketNewsExecutionState.COMPLETED);
        assertThat(result.callCount()).isEqualTo(4000);
        assertThat(result.completedWorkUnits()).isEqualTo(2);
        verify(repository, never()).planGeneral(any(), any(), any(), anyInt());
        verify(provider).search(org.mockito.ArgumentMatchers.argThat(query -> query.start() == 201));
        verify(repository)
                .recordWorkUnitPageProgress(eq(WORK_UNIT_ID), eq(201), eq(3), eq(4), eq(NOW.minusSeconds(60)));
        verify(repository)
                .finishWorkUnit(
                        eq(WORK_UNIT_ID),
                        eq(MarketNewsWorkUnitState.COMPLETED),
                        eq(3),
                        eq(4),
                        eq(NOW.minusSeconds(60)),
                        eq(true),
                        org.mockito.ArgumentMatchers.isNull(),
                        any());
    }

    @Test
    @DisplayName("raw를 먼저 저장한 뒤 정규화·분류하고 cutoff를 만난 전국 scope를 발행한다")
    void storesRawBeforeArticleAndPublishesEligibleScope() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        MarketNewsCollectionExecution execution = execution(nationalUnit(), NOW.minusSeconds(2 * 60 * 60));
        NewsProviderItem raw = raw(1, NOW.minusSeconds(3 * 60 * 60));
        PublishedNewsSnapshot snapshot = new PublishedNewsSnapshot(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174302"),
                MarketNewsScopeType.NATIONWIDE,
                null,
                NOW,
                NOW.minusSeconds(60));
        when(repository.planGeneral(any(), any(), any(), anyInt())).thenReturn(execution);
        when(provider.search(any())).thenReturn(new NewsProviderPage(1, 1, 1, List.of(raw)));
        when(repository.upsertArticle(any(), any())).thenReturn(91L);
        when(repository.publishEligibleScopes(eq(EXECUTION_ID), any())).thenReturn(List.of(snapshot));

        MarketNewsCollectionResult result = service(repository, provider, new NaverNewsItemNormalizer(), cache)
                .collectGeneral("123e4567-e89b-12d3-a456-426614174303", NOW, 4000);

        assertThat(result.state()).isEqualTo(MarketNewsExecutionState.COMPLETED);
        assertThat(result.callCount()).isEqualTo(1);
        InOrder rawFirst = inOrder(repository);
        rawFirst.verify(repository).saveRawItems(eq(WORK_UNIT_ID), eq(List.of(raw)), any());
        rawFirst.verify(repository).upsertArticle(any(), any());
        verify(repository)
                .saveRelation(
                        eq(91L),
                        eq("NEWS_V2"),
                        eq(MarketNewsCategory.TRANSACTION_PRICE),
                        org.mockito.ArgumentMatchers.argThat(
                                relation -> relation.relationType() == MarketNewsRelationType.NATIONWIDE));
        verify(cache).publish(snapshot);
    }

    @Test
    @DisplayName("start=1000까지 cutoff에 닿지 못하면 scope를 TRUNCATED로 남기고 발행하지 않는다")
    void truncatesWhenPaginationCannotReachCutoff() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        when(repository.planGeneral(any(), any(), any(), anyInt()))
                .thenReturn(execution(nationalUnit(), NOW.minusSeconds(2 * 60 * 60)));
        when(provider.search(any())).thenAnswer(invocation -> {
            NewsProviderQuery query = invocation.getArgument(0);
            return new NewsProviderPage(2000, query.start(), 1, List.of(raw(query.start(), NOW.minusSeconds(60))));
        });
        when(repository.upsertArticle(any(), any())).thenReturn(91L);

        MarketNewsCollectionResult result = service(repository, provider, new NaverNewsItemNormalizer(), cache)
                .collectGeneral("123e4567-e89b-12d3-a456-426614174304", NOW, 4000);

        assertThat(result.state()).isEqualTo(MarketNewsExecutionState.FAILED);
        assertThat(result.truncatedWorkUnits()).isEqualTo(1);
        assertThat(result.callCount()).isEqualTo(10);
        verify(repository)
                .finishWorkUnit(
                        eq(WORK_UNIT_ID),
                        eq(MarketNewsWorkUnitState.TRUNCATED),
                        eq(10),
                        eq(10),
                        any(),
                        eq(false),
                        eq(MarketNewsFailureKind.CUTOFF_NOT_REACHED),
                        any());
        verify(repository, never()).publishEligibleScopes(any(), any());
        verifyNoInteractions(cache);
    }

    @Test
    @DisplayName("30일 BOOTSTRAP은 start=1000 한계의 TRUNCATED 근거를 남기고 수집 범위를 발행한다")
    void publishesTruncatedBootstrapWithinCollectedRange() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        PublishedNewsSnapshot snapshot = new PublishedNewsSnapshot(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174306"),
                MarketNewsScopeType.NATIONWIDE,
                null,
                NOW,
                NOW.minusSeconds(60));
        when(repository.planGeneral(any(), any(), any(), anyInt()))
                .thenReturn(execution("BOOTSTRAP", nationalUnit(), NOW.minusSeconds(30L * 24 * 60 * 60)));
        when(provider.search(any())).thenAnswer(invocation -> {
            NewsProviderQuery query = invocation.getArgument(0);
            return new NewsProviderPage(2000, query.start(), 1, List.of(raw(query.start(), NOW.minusSeconds(60))));
        });
        when(repository.upsertArticle(any(), any())).thenReturn(91L);
        when(repository.publishEligibleScopes(eq(EXECUTION_ID), any())).thenReturn(List.of(snapshot));

        MarketNewsCollectionResult result = service(repository, provider, new NaverNewsItemNormalizer(), cache)
                .collectGeneral("BOOTSTRAP:123e4567-e89b-12d3-a456-426614174305", NOW, 4000);

        assertThat(result.state()).isEqualTo(MarketNewsExecutionState.PARTIAL);
        assertThat(result.truncatedWorkUnits()).isEqualTo(1);
        verify(repository).publishEligibleScopes(eq(EXECUTION_ID), any());
        verify(cache).publish(snapshot);
    }

    @Test
    @DisplayName("transient provider 오류는 budget에 포함해 한 번만 재시도한다")
    void retriesTransientProviderFailureOnce() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        when(repository.planGeneral(any(), any(), any(), anyInt()))
                .thenReturn(execution(nationalUnit(), NOW.minusSeconds(2 * 60 * 60)));
        when(provider.search(any()))
                .thenThrow(new NewsProviderCallException(NewsProviderFailureType.TRANSIENT, "timeout", 0, null))
                .thenReturn(new NewsProviderPage(1, 1, 1, List.of(raw(1, NOW.minusSeconds(3 * 60 * 60)))));
        when(repository.upsertArticle(any(), any())).thenReturn(91L);
        when(repository.publishEligibleScopes(any(), any())).thenReturn(List.of());

        MarketNewsCollectionResult result = service(repository, provider, new NaverNewsItemNormalizer(), cache)
                .collectGeneral("123e4567-e89b-12d3-a456-426614174305", NOW, 4000);

        assertThat(result.state()).isEqualTo(MarketNewsExecutionState.COMPLETED);
        assertThat(result.callCount()).isEqualTo(2);
        verify(provider, org.mockito.Mockito.times(2)).search(any());
        verify(repository, org.mockito.Mockito.times(2)).incrementExecutionCallCount(EXECUTION_ID);
    }

    @Test
    @DisplayName("KST daily budget이 소진되면 현재와 남은 unit을 SKIPPED_BUDGET 처리한다")
    void stopsWhenDailyBudgetIsExhausted() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        when(repository.planGeneral(any(), any(), any(), anyInt()))
                .thenReturn(execution(twoNationalUnits(), NOW.minusSeconds(2 * 60 * 60)));
        org.mockito.Mockito.doThrow(new NewsCallBudgetExceededException())
                .when(repository)
                .incrementExecutionCallCount(EXECUTION_ID);

        MarketNewsCollectionResult result = service(repository, provider, new NaverNewsItemNormalizer(), cache)
                .collectGeneral("123e4567-e89b-12d3-a456-426614174306", NOW, 4000);

        assertThat(result.state()).isEqualTo(MarketNewsExecutionState.FAILED);
        verify(repository)
                .finishWorkUnit(
                        eq(WORK_UNIT_ID),
                        eq(MarketNewsWorkUnitState.SKIPPED_BUDGET),
                        eq(0),
                        eq(0),
                        org.mockito.ArgumentMatchers.isNull(),
                        eq(false),
                        eq(MarketNewsFailureKind.DAILY_CALL_BUDGET),
                        any());
        verify(repository).markRemainingSkippedBudget(eq(EXECUTION_ID), any());
        verifyNoInteractions(provider, cache);
    }

    @Test
    @DisplayName("부동산 allowlist만 있고 category 근거가 없는 기사는 raw rejection으로 보존한다")
    void rejectsUnclassifiedRealEstateItem() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        NewsProviderItem raw = new NewsProviderItem(
                "아파트 화재 현장",
                "https://news.example.test/fire",
                null,
                "주민 대피",
                java.time.ZonedDateTime.ofInstant(NOW.minusSeconds(3 * 60 * 60), ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME),
                1,
                1);
        when(repository.planGeneral(any(), any(), any(), anyInt()))
                .thenReturn(execution(nationalUnit(), NOW.minusSeconds(2 * 60 * 60)));
        when(provider.search(any())).thenReturn(new NewsProviderPage(1, 1, 1, List.of(raw)));
        when(repository.publishEligibleScopes(any(), any())).thenReturn(List.of());

        MarketNewsCollectionResult result = service(repository, provider, new NaverNewsItemNormalizer(), cache)
                .collectGeneral("123e4567-e89b-12d3-a456-426614174308", NOW, 4000);

        assertThat(result.state()).isEqualTo(MarketNewsExecutionState.COMPLETED);
        verify(repository).rejectRawItem(WORK_UNIT_ID, raw, NewsRejectionReason.NOT_REAL_ESTATE_RELEVANT);
        verify(repository, never()).upsertArticle(any(), any());
    }

    @Test
    @DisplayName("정규화 거부 item도 원천 pubDate가 오래됐으면 현재 page에서 cutoff 완료로 종료한다")
    void rejectedItemPubDateStopsAtCutoffWithoutAnotherPage() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        NewsProviderItem unsafe = new NewsProviderItem(
                "아파트 정책", "javascript:alert(1)", null, "부동산 정책", "Fri, 24 Jul 2026 15:00:00 +0900", 1, 1);
        when(repository.planGeneral(any(), any(), any(), anyInt()))
                .thenReturn(execution(nationalUnit(), NOW.minusSeconds(2 * 60 * 60)));
        when(provider.search(any()))
                .thenReturn(new NewsProviderPage(1, 1, 1, List.of(unsafe)))
                .thenReturn(new NewsProviderPage(1, 101, 0, List.of()));
        when(repository.publishEligibleScopes(any(), any())).thenReturn(List.of());

        MarketNewsCollectionResult result = service(repository, provider, new NaverNewsItemNormalizer(), cache)
                .collectGeneral("123e4567-e89b-12d3-a456-426614174309", NOW, 4000);

        assertThat(result.callCount()).isEqualTo(1);
        verify(repository).rejectRawItem(WORK_UNIT_ID, unsafe, NewsRejectionReason.INVALID_URL);
    }

    @Test
    @DisplayName("재개된 provider 위치의 payload가 바뀌면 기존 raw evidence를 덮지 않고 unit을 실패시킨다")
    void failsUnitWhenProviderPositionPayloadChanged() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        NewsItemNormalizationGateway normalizer = mock(NewsItemNormalizationGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        NewsProviderItem changed = raw(1, NOW.minusSeconds(60));
        when(repository.planGeneral(any(), any(), any(), anyInt()))
                .thenReturn(execution(nationalUnit(), NOW.minusSeconds(2 * 60 * 60)));
        when(provider.search(any())).thenReturn(new NewsProviderPage(1, 1, 1, List.of(changed)));
        org.mockito.Mockito.doThrow(new RawNewsPositionConflictException())
                .when(repository)
                .requireRawItemMatch(WORK_UNIT_ID, changed);

        MarketNewsCollectionResult result =
                service(repository, provider, normalizer, cache).collectGeneral("NEWS-RAW-CONFLICT", NOW, 4000);

        assertThat(result.state()).isEqualTo(MarketNewsExecutionState.FAILED);
        verify(repository)
                .finishWorkUnit(
                        eq(WORK_UNIT_ID),
                        eq(MarketNewsWorkUnitState.FAILED),
                        eq(1),
                        eq(1),
                        org.mockito.ArgumentMatchers.isNull(),
                        eq(false),
                        eq(MarketNewsFailureKind.RAW_POSITION_CONFLICT),
                        any());
        verifyNoInteractions(normalizer, cache);
        verify(repository, never()).upsertArticle(any(), any());
    }

    @Test
    @DisplayName("인증 실패는 재시도하지 않고 execution을 실패시킨다")
    void doesNotRetryAuthenticationFailure() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        when(repository.planGeneral(any(), any(), any(), anyInt()))
                .thenReturn(execution(twoNationalUnits(), NOW.minusSeconds(2 * 60 * 60)));
        when(provider.search(any()))
                .thenThrow(new NewsProviderCallException(NewsProviderFailureType.AUTHENTICATION, "denied", null, null));

        MarketNewsCollectionResult result = service(repository, provider, new NaverNewsItemNormalizer(), cache)
                .collectGeneral("123e4567-e89b-12d3-a456-426614174310", NOW, 4000);

        assertThat(result.state()).isEqualTo(MarketNewsExecutionState.FAILED);
        assertThat(result.callCount()).isEqualTo(1);
        verify(provider).search(any());
        verify(repository).markRemainingSkippedBudget(eq(EXECUTION_ID), any());
        verifyNoInteractions(cache);
    }

    @Test
    @DisplayName("provider 일일 quota 실패는 남은 unit을 skip해도 execution 원인으로 보존한다")
    void preservesDailyQuotaAsExecutionFailureWhenRemainingUnitsAreSkipped() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        when(repository.planGeneral(any(), any(), any(), anyInt()))
                .thenReturn(execution(twoNationalUnits(), NOW.minusSeconds(2 * 60 * 60)));
        when(provider.search(any()))
                .thenThrow(new NewsProviderCallException(NewsProviderFailureType.DAILY_QUOTA, "quota", null, null));

        MarketNewsCollectionResult result = service(repository, provider, new NaverNewsItemNormalizer(), cache)
                .collectGeneral("123e4567-e89b-12d3-a456-426614174317", NOW, 4000);

        assertThat(result.state()).isEqualTo(MarketNewsExecutionState.FAILED);
        verify(repository).markRemainingSkippedBudget(eq(EXECUTION_ID), any());
        verify(repository)
                .finishExecution(
                        eq(EXECUTION_ID),
                        eq(MarketNewsExecutionState.FAILED),
                        eq(MarketNewsFailureKind.DAILY_QUOTA),
                        any());
    }

    @Test
    @DisplayName("DB publication 뒤 Redis pointer 장애는 evidence를 되돌리지 않고 failure kind로 기록한다")
    void recordsCachePublicationFailureWithoutUndoingSnapshot() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        PublishedNewsSnapshot snapshot = new PublishedNewsSnapshot(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174311"),
                MarketNewsScopeType.NATIONWIDE,
                null,
                NOW,
                NOW.minusSeconds(60));
        when(repository.planGeneral(any(), any(), any(), anyInt()))
                .thenReturn(execution(nationalUnit(), NOW.minusSeconds(2 * 60 * 60)));
        when(provider.search(any()))
                .thenReturn(new NewsProviderPage(1, 1, 1, List.of(raw(1, NOW.minusSeconds(3 * 60 * 60)))));
        when(repository.upsertArticle(any(), any())).thenReturn(91L);
        when(repository.publishEligibleScopes(any(), any())).thenReturn(List.of(snapshot));
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
                .when(cache)
                .publish(snapshot);

        MarketNewsCollectionResult result = service(repository, provider, new NaverNewsItemNormalizer(), cache)
                .collectGeneral("123e4567-e89b-12d3-a456-426614174312", NOW, 4000);

        assertThat(result.state()).isEqualTo(MarketNewsExecutionState.COMPLETED);
        verify(repository)
                .finishExecution(
                        eq(EXECUTION_ID),
                        eq(MarketNewsExecutionState.COMPLETED),
                        eq(MarketNewsFailureKind.CACHE_PUBLICATION),
                        any());
    }

    @Test
    @DisplayName("주요 단지 query가 직접 단지 근거를 만들지 못하면 raw를 모호성 근거로 보존한다")
    void rejectsMajorComplexItemWithoutDirectEvidence() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        NewsProviderItem raw = raw(1, NOW.minusSeconds(3 * 60 * 60));
        when(repository.planMajorComplex(any(), any(), any(), anyInt()))
                .thenReturn(execution(majorComplexUnit(), NOW.minusSeconds(2 * 60 * 60)));
        when(provider.search(any())).thenReturn(new NewsProviderPage(1, 1, 1, List.of(raw)));

        MarketNewsCollectionResult result = service(repository, provider, new NaverNewsItemNormalizer(), cache)
                .collectMajorComplex("123e4567-e89b-12d3-a456-426614174313", NOW, 4000);

        assertThat(result.state()).isEqualTo(MarketNewsExecutionState.COMPLETED);
        verify(repository).rejectRawItem(WORK_UNIT_ID, raw, NewsRejectionReason.COMPLEX_AMBIGUOUS);
        verify(repository, never()).upsertArticle(any(), any());
    }

    @Test
    @DisplayName("시도 query도 기사 본문에 지역 근거가 없으면 relation을 만들지 않는다")
    void rejectsSidoItemWithoutArticleRegionEvidence() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        NewsProviderItem raw = new NewsProviderItem(
                "아파트 거래 가격",
                "https://news.example.test/no-region",
                null,
                "주택 매매 거래",
                java.time.ZonedDateTime.ofInstant(NOW.minusSeconds(3 * 60 * 60), ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME),
                1,
                1);
        when(repository.planGeneral(any(), any(), any(), anyInt()))
                .thenReturn(execution(sidoUnit(), NOW.minusSeconds(2 * 60 * 60)));
        when(provider.search(any())).thenReturn(new NewsProviderPage(1, 1, 1, List.of(raw)));

        MarketNewsCollectionResult result = service(repository, provider, new NaverNewsItemNormalizer(), cache)
                .collectGeneral("123e4567-e89b-12d3-a456-426614174314", NOW, 4000);

        assertThat(result.state()).isEqualTo(MarketNewsExecutionState.COMPLETED);
        verify(repository).rejectRawItem(WORK_UNIT_ID, raw, NewsRejectionReason.REGION_AMBIGUOUS);
    }

    @Test
    @DisplayName("미래 시각과 30일 보관 범위 밖 기사는 article 생성 전에 각각 거부한다")
    void rejectsFutureAndExpiredItemsBeforeArticleCreation() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        NewsProviderItem future = raw(1, NOW.plusSeconds(6 * 60));
        NewsProviderItem expired = raw(2, NOW.minusSeconds(31L * 24 * 60 * 60));
        when(repository.planGeneral(any(), any(), any(), anyInt()))
                .thenReturn(execution(nationalUnit(), NOW.minusSeconds(2 * 60 * 60)));
        when(provider.search(any())).thenReturn(new NewsProviderPage(2, 1, 2, List.of(future, expired)));

        MarketNewsCollectionResult result = service(repository, provider, new NaverNewsItemNormalizer(), cache)
                .collectGeneral("123e4567-e89b-12d3-a456-426614174315", NOW, 4000);

        assertThat(result.state()).isEqualTo(MarketNewsExecutionState.COMPLETED);
        verify(repository).rejectRawItem(WORK_UNIT_ID, future, NewsRejectionReason.INVALID_PROVIDED_AT);
        verify(repository).rejectRawItem(WORK_UNIT_ID, expired, NewsRejectionReason.OUTSIDE_RETENTION_WINDOW);
        verify(repository, never()).upsertArticle(any(), any());
    }

    @Test
    @DisplayName("예상하지 못한 normalization 장애는 work unit INTERNAL 실패로 격리한다")
    void isolatesUnexpectedNormalizationFailure() {
        MarketNewsCollectionRepository repository = mock(MarketNewsCollectionRepository.class);
        NewsProviderGateway provider = mock(NewsProviderGateway.class);
        NewsItemNormalizationGateway normalizer = mock(NewsItemNormalizationGateway.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        NewsProviderItem raw = raw(1, NOW.minusSeconds(3 * 60 * 60));
        when(repository.planGeneral(any(), any(), any(), anyInt()))
                .thenReturn(execution(nationalUnit(), NOW.minusSeconds(2 * 60 * 60)));
        when(provider.search(any())).thenReturn(new NewsProviderPage(1, 1, 1, List.of(raw)));
        when(normalizer.tryNormalize(raw)).thenThrow(new IllegalStateException("unexpected"));

        MarketNewsCollectionResult result = service(repository, provider, normalizer, cache)
                .collectGeneral("123e4567-e89b-12d3-a456-426614174316", NOW, 4000);

        assertThat(result.state()).isEqualTo(MarketNewsExecutionState.FAILED);
        verify(repository)
                .finishWorkUnit(
                        eq(WORK_UNIT_ID),
                        eq(MarketNewsWorkUnitState.FAILED),
                        eq(1),
                        eq(1),
                        org.mockito.ArgumentMatchers.isNull(),
                        eq(false),
                        eq(MarketNewsFailureKind.INTERNAL),
                        any());
    }

    private static MarketNewsCollectionService service(
            MarketNewsCollectionRepository repository,
            NewsProviderGateway provider,
            NewsItemNormalizationGateway normalizer,
            MarketNewsPublicationCache cache) {
        return new MarketNewsCollectionService(
                repository,
                provider,
                normalizer,
                cache,
                new MarketNewsClassificationPolicy(),
                new MarketNewsRelationPolicy(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MarketNewsCollectionExecution execution(MarketNewsWorkUnitSpec unit, Instant overlapCutoff) {
        return execution(List.of(unit), overlapCutoff);
    }

    private static MarketNewsCollectionExecution execution(
            String executionType, MarketNewsWorkUnitSpec unit, Instant overlapCutoff) {
        return new MarketNewsCollectionExecution(
                EXECUTION_ID,
                "request",
                executionType,
                "NEWS_V2",
                NOW,
                overlapCutoff,
                4000,
                0,
                1,
                0,
                0,
                0,
                0,
                List.of(unit));
    }

    private static MarketNewsCollectionExecution execution(List<MarketNewsWorkUnitSpec> units, Instant overlapCutoff) {
        return new MarketNewsCollectionExecution(
                EXECUTION_ID,
                "request",
                "GENERAL",
                "NEWS_V2",
                NOW,
                overlapCutoff,
                4000,
                0,
                units.size(),
                0,
                0,
                0,
                0,
                units);
    }

    private static MarketNewsWorkUnitSpec nationalUnit() {
        return new MarketNewsWorkUnitSpec(
                WORK_UNIT_ID,
                1,
                MarketNewsWorkUnitKind.NATIONAL_CATEGORY,
                MarketNewsScopeType.NATIONWIDE,
                null,
                null,
                MarketNewsCategory.TRANSACTION_PRICE,
                "아파트 매매 거래 가격",
                null,
                List.of());
    }

    private static MarketNewsWorkUnitSpec resumedNationalUnit() {
        return new MarketNewsWorkUnitSpec(
                WORK_UNIT_ID,
                1,
                MarketNewsWorkUnitKind.NATIONAL_CATEGORY,
                MarketNewsScopeType.NATIONWIDE,
                null,
                null,
                MarketNewsCategory.TRANSACTION_PRICE,
                "아파트 매매 거래 가격",
                null,
                List.of(),
                201,
                2,
                4,
                NOW.minusSeconds(60));
    }

    private static List<MarketNewsWorkUnitSpec> twoNationalUnits() {
        return List.of(
                nationalUnit(),
                new MarketNewsWorkUnitSpec(
                        SECOND_WORK_UNIT_ID,
                        2,
                        MarketNewsWorkUnitKind.NATIONAL_CATEGORY,
                        MarketNewsScopeType.NATIONWIDE,
                        null,
                        null,
                        MarketNewsCategory.POLICY,
                        "부동산 정책 아파트",
                        null,
                        List.of()));
    }

    private static MarketNewsWorkUnitSpec sidoUnit() {
        return new MarketNewsWorkUnitSpec(
                WORK_UNIT_ID,
                1,
                MarketNewsWorkUnitKind.SIDO,
                MarketNewsScopeType.SIDO,
                "11",
                "서울",
                null,
                "서울 아파트 부동산",
                null,
                List.of());
    }

    private static MarketNewsWorkUnitSpec majorComplexUnit() {
        return new MarketNewsWorkUnitSpec(
                WORK_UNIT_ID,
                1,
                MarketNewsWorkUnitKind.MAJOR_COMPLEX,
                MarketNewsScopeType.SIDO,
                "11",
                "서울",
                null,
                "강남구 샘플동 샘플아파트 아파트",
                null,
                List.of());
    }

    private static NewsProviderItem raw(int start, Instant providedAt) {
        return new NewsProviderItem(
                "서울 아파트 거래 가격",
                "https://news.example.test/article/" + start,
                null,
                "주택 매매 거래",
                java.time.ZonedDateTime.ofInstant(providedAt, ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME),
                start,
                1);
    }
}
