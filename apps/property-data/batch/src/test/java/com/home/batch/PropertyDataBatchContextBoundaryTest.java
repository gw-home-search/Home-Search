package com.home.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.coordinate.lookup.ParcelCoordinateResolver;
import com.home.application.ingest.normalization.NormalizedTradeRepository;
import com.home.application.ingest.raw.RawTradeIngestRepository;
import com.home.application.ingest.rtms.RtmsMonthlyRefreshUseCase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
			"home.ingest.raw-reconcile.enabled=false",
			"home.trade.partition.maintenance.enabled=false",
			"home.test.non-batch.enabled=true"
		);

	@Test
	@DisplayName("비-Batch feature를 활성화해도 Batch context에는 유입되지 않는다")
	void enabledNonBatchFeatureIsNotScanned() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context)
				.hasBean("rtmsDailyRefreshJob")
				.hasBean("complexBuildingMetadataJob")
				.doesNotHaveBean("complexMetadataReplayJob")
				.hasBean("coordinatePreflightStep")
				.hasBean("rtmsDailyMonthlyIngestStep")
				.hasBean("regionUnitSyncStep")
				.hasSingleBean(ParcelCoordinateResolver.class)
				.hasSingleBean(RtmsMonthlyRefreshUseCase.class)
				.hasSingleBean(RawTradeIngestRepository.class)
				.hasSingleBean(NormalizedTradeRepository.class)
				.hasSingleBean(PlatformTransactionManager.class)
				.hasSingleBean(JobRepository.class);
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
}
