package com.home.infrastructure.persistence.ingest;

import com.home.application.ingest.backfill.RtmsBackfillChunkRepository;
import com.home.application.ingest.backfill.RtmsBackfillJobRepository;
import com.home.application.ingest.run.RtmsIngestRunReportRepository;
import com.home.application.ingest.run.RtmsIngestRunRepository;
import com.home.infrastructure.persistence.ingest.backfill.JdbcRtmsBackfillChunkRepository;
import com.home.infrastructure.persistence.ingest.backfill.JdbcRtmsBackfillJobRepository;
import com.home.infrastructure.persistence.ingest.run.JdbcRtmsIngestRunReportRepository;
import com.home.infrastructure.persistence.ingest.run.JdbcRtmsIngestRunRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class RtmsIngestRunPersistenceConfiguration {

	@Bean
	@Lazy
	RtmsIngestRunRepository rtmsIngestRunRepository(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return new JdbcRtmsIngestRunRepository(IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider));
	}

	@Bean
	@Lazy
	RtmsIngestRunReportRepository rtmsIngestRunReportRepository(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return new JdbcRtmsIngestRunReportRepository(
			IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider)
		);
	}

	@Bean
	@Lazy
	RtmsBackfillJobRepository rtmsBackfillJobRepository(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return new JdbcRtmsBackfillJobRepository(
			IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider),
			java.time.Clock.systemUTC()
		);
	}

	@Bean
	@Lazy
	RtmsBackfillChunkRepository rtmsBackfillChunkRepository(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return new JdbcRtmsBackfillChunkRepository(
			IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider),
			java.time.Clock.systemUTC()
		);
	}
}
