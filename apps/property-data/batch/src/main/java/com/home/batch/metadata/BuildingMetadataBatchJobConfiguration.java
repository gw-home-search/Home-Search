package com.home.batch.metadata;

import com.home.application.ingest.buildingmetadata.BuildingMetadataBatchService;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

@Configuration(proxyBeanMethods = false)
class BuildingMetadataBatchJobConfiguration {
	@Bean BuildingMetadataExecutionLock buildingMetadataExecutionLock(DataSource dataSource) {
		return new BuildingMetadataExecutionLock(dataSource);
	}

	@Bean @Lazy Job complexBuildingMetadataJob(JobRepository repository, Step complexBuildingMetadataStep) {
		return new JobBuilder("complexBuildingMetadataJob", repository).start(complexBuildingMetadataStep).build();
	}

	@Bean @Lazy Step complexBuildingMetadataStep(JobRepository repository, PlatformTransactionManager transactionManager,
		BuildingMetadataBatchService service, BuildingMetadataExecutionLock executionLock,
		@Value("${complex.metadata.daily-request-quota:1000}") int dailyQuota) {
		return step("complexBuildingMetadataStep", repository, transactionManager,
			new BuildingMetadataCollectTasklet(service, executionLock, dailyQuota));
	}

	private Step step(String name, JobRepository repository, PlatformTransactionManager transactionManager,
		org.springframework.batch.core.step.tasklet.Tasklet tasklet) {
		DefaultTransactionAttribute attribute = new DefaultTransactionAttribute();
		attribute.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
		return new StepBuilder(name, repository).tasklet(tasklet, transactionManager).transactionAttribute(attribute).build();
	}
}
