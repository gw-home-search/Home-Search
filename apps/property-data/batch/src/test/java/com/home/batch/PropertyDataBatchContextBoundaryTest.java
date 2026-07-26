package com.home.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.home.application.coordinate.lookup.ParcelCoordinateResolver;
import com.home.application.event.PropertyEventOutboxRelayService;
import com.home.application.event.PropertyEventOutboxRetentionService;
import com.home.application.ingest.metadata.OdcComplexMetadataResolver;
import com.home.application.ingest.metadata.OdcMetadataGapFillRepository;
import com.home.application.ingest.metadata.OdcMetadataGapFillService;
import com.home.application.ingest.normalization.NormalizedTradeRepository;
import com.home.application.ingest.raw.RawTradeIngestRepository;
import com.home.application.ingest.rtms.RtmsMonthlyRefreshUseCase;
import com.home.application.insight.generation.MarketInsightWeeklyBuildService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class PropertyDataBatchContextBoundaryTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertyDataBatchApplication.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:batch-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=",
                    "spring.batch.job.enabled=false",
                    "spring.batch.jdbc.initialize-schema=always",
                    "spring.batch.jdbc.platform=h2",
                    "spring.batch.jdbc.table-prefix=BATCH_",
                    "spring.flyway.enabled=false",
                    "spring.main.lazy-initialization=true",
                    "home.insight.trade.enabled=true",
                    "home.ingest.raw-reconcile.enabled=false",
                    "home.trade.partition.maintenance.enabled=false",
                    "home.test.non-batch.enabled=true");

    @Test
    @DisplayName("packaged entrypoint는 building profile 운영 job 4종을 허용한다")
    void profileJobsAreSupportedByPackagedEntrypoint() {
        assertThat(PropertyDataBatchApplication.supportsJobName("complexBuildingRegisterProfileReplayJob"))
                .isTrue();
        assertThat(PropertyDataBatchApplication.supportsJobName("complexBuildingRegisterProfileCollectJob"))
                .isTrue();
        assertThat(PropertyDataBatchApplication.supportsJobName("complexBuildingRegisterProfileAnalyzeJob"))
                .isTrue();
        assertThat(PropertyDataBatchApplication.supportsJobName("complexBuildingRegisterProfileProjectJob"))
                .isTrue();
        assertThat(PropertyDataBatchApplication.supportsJobName("complexBuildingRegisterProfileRepairJob"))
                .isTrue();
        assertThat(PropertyDataBatchApplication.supportsJobName("legalDongCodeMappingImportJob"))
                .isTrue();
        assertThat(PropertyDataBatchApplication.supportsJobName("marketInsightRolling7dJob"))
                .isTrue();
        assertThat(PropertyDataBatchApplication.supportsJobName("marketInsightWeeklyJob"))
                .isFalse();
        assertThat(PropertyDataBatchApplication.supportsJobName("propertyEventRelayJob"))
                .isTrue();
        assertThat(PropertyDataBatchApplication.supportsJobName("propertyEventOutboxRetentionJob"))
                .isTrue();
    }

    @Test
    @DisplayName("property event relay는 명시적 opt-in일 때만 job과 service를 등록한다")
    void propertyEventRelayRequiresExplicitOptIn() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean("propertyEventRelayJob")
                .doesNotHaveBean("propertyEventRelayStep")
                .doesNotHaveBean("propertyEventOutboxRelayService"));

        contextRunner
                .withPropertyValues("home.events.relay.enabled=true", "spring.kafka.bootstrap-servers=127.0.0.1:9092")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasBean("propertyEventRelayJob")
                            .hasBean("propertyEventRelayStep")
                            .hasSingleBean(PropertyEventOutboxRelayService.class);
                });
    }

    @Test
    @DisplayName("property event outbox retention은 relay와 독립된 명시적 opt-in으로 등록한다")
    void propertyEventOutboxRetentionRequiresExplicitOptIn() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean("propertyEventOutboxRetentionJob")
                .doesNotHaveBean("propertyEventOutboxRetentionStep")
                .doesNotHaveBean("propertyEventOutboxRetentionService"));

        contextRunner.withPropertyValues("home.events.retention.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .hasBean("propertyEventOutboxRetentionJob")
                    .hasBean("propertyEventOutboxRetentionStep")
                    .hasSingleBean(PropertyEventOutboxRetentionService.class)
                    .doesNotHaveBean("propertyEventOutboxRelayService");
        });
    }

    @Test
    @DisplayName("비-Batch feature를 활성화해도 Batch context에는 유입되지 않는다")
    void enabledNonBatchFeatureIsNotScanned() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .hasBean("rtmsDailyRefreshJob")
                    .hasBean("complexBuildingMetadataJob")
                    .hasBean("complexOdcMetadataGapFillJob")
                    .hasBean("complexBuildingRegisterCollectJob")
                    .hasBean("complexBuildingRatioProjectJob")
                    .doesNotHaveBean("complexMetadataReplayJob")
                    .hasBean("coordinatePreflightStep")
                    .hasBean("rtmsDailyMonthlyIngestStep")
                    .hasBean("regionUnitSyncStep")
                    .doesNotHaveBean("marketInsightWeeklyJob")
                    .hasBean("marketInsightRolling7dJob")
                    .hasBean("marketInsightRolling7dStep")
                    .hasSingleBean(MarketInsightWeeklyBuildService.class)
                    .hasSingleBean(ParcelCoordinateResolver.class)
                    .hasSingleBean(RtmsMonthlyRefreshUseCase.class)
                    .hasSingleBean(OdcComplexMetadataResolver.class)
                    .hasSingleBean(RawTradeIngestRepository.class)
                    .hasSingleBean(NormalizedTradeRepository.class)
                    .hasSingleBean(PlatformTransactionManager.class)
                    .hasSingleBean(JobRepository.class);
            assertThat(context.getBean("rtmsDailyRefreshJob", SimpleJob.class).getStepNames())
                    .containsSubsequence(
                            "monthlyIngestStep",
                            "regionUnitSyncStep",
                            "marketInsightDailyStep",
                            "marketInsightRolling7dStep");
            assertThat(context).doesNotHaveBean("testOnlyNonBatchFeature");
            assertThat(context)
                    .doesNotHaveBean("mapUseCase")
                    .doesNotHaveBean("predictionUseCase")
                    .doesNotHaveBean("propertyReadUseCase")
                    .doesNotHaveBean("complexCoordinateReadinessRunner")
                    .doesNotHaveBean("rawIngestReconciliationRunner")
                    .doesNotHaveBean("rtmsDailyRefreshScheduler")
                    .doesNotHaveBean("mapController")
                    .doesNotHaveBean("apiExceptionHandler");
        });
    }

    @Test
    @DisplayName("Batch ODC service는 설정된 실제 resolver를 사용하고 미구성 fallback을 캡처하지 않는다")
    void odcGapFillServiceUsesConfiguredResolver() {
        OdcMetadataGapFillRepository repository = mock(OdcMetadataGapFillRepository.class);
        contextRunner
                .withPropertyValues("ODC_SERVICE_KEY=packaged-test")
                .withInitializer(context -> context.addBeanFactoryPostProcessor(beanFactory -> {
                    for (String beanName : beanFactory.getBeanDefinitionNames()) {
                        beanFactory.getBeanDefinition(beanName).setLazyInit(true);
                    }
                }))
                .withBean(
                        "testOdcMetadataGapFillRepository",
                        OdcMetadataGapFillRepository.class,
                        () -> repository,
                        definition -> definition.setPrimary(true))
                .run(context -> {
                    OdcMetadataGapFillService service = context.getBean(OdcMetadataGapFillService.class);
                    assertThatCode(() -> service.fill(
                                    1, null, 1L, java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174099")))
                            .doesNotThrowAnyException();
                });
    }
}
