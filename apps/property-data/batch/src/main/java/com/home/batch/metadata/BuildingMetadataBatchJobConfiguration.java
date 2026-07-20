package com.home.batch.metadata;

import com.home.application.ingest.buildingmetadata.BuildingMetadataBatchService;
import com.home.application.ingest.buildingregister.BuildingRatioProjectionService;
import com.home.application.ingest.buildingregister.BuildingRegisterCampaignService;
import com.home.application.ingest.metadata.OdcMetadataGapFillService;
import com.home.infrastructure.external.complex.ComplexMetadataProperties;
import javax.sql.DataSource;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ComplexMetadataProperties.class)
class BuildingMetadataBatchJobConfiguration {
    @Bean
    BuildingMetadataExecutionLock buildingMetadataExecutionLock(DataSource dataSource) {
        return new BuildingMetadataExecutionLock(dataSource);
    }

    @Bean
    @Lazy
    Job complexBuildingMetadataJob(JobRepository repository, Step complexBuildingMetadataStep) {
        return new JobBuilder("complexBuildingMetadataJob", repository)
                .start(complexBuildingMetadataStep)
                .build();
    }

    @Bean
    @Lazy
    Job complexOdcMetadataGapFillJob(JobRepository repository, Step complexOdcMetadataGapFillStep) {
        return new JobBuilder("complexOdcMetadataGapFillJob", repository)
                .start(complexOdcMetadataGapFillStep)
                .build();
    }

    @Bean
    @Lazy
    Job complexBuildingRatioProjectJob(JobRepository repository, Step complexBuildingRatioProjectStep) {
        return new JobBuilder("complexBuildingRatioProjectJob", repository)
                .start(complexBuildingRatioProjectStep)
                .build();
    }

    @Bean
    @Lazy
    Job complexBuildingRegisterCollectJob(JobRepository repository, Step complexBuildingRegisterCollectStep) {
        return new JobBuilder("complexBuildingRegisterCollectJob", repository)
                .start(complexBuildingRegisterCollectStep)
                .build();
    }

    @Bean
    @Lazy
    Step complexOdcMetadataGapFillStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            OdcMetadataGapFillService service,
            BuildingMetadataExecutionLock executionLock,
            ComplexMetadataProperties properties) {
        return step(
                "complexOdcMetadataGapFillStep",
                repository,
                transactionManager,
                new OdcMetadataGapFillTasklet(service, executionLock, properties.dailyRequestQuota()));
    }

    @Bean
    @Lazy
    Step complexBuildingMetadataStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            BuildingMetadataBatchService service,
            BuildingMetadataExecutionLock executionLock,
            ComplexMetadataProperties properties) {
        return step(
                "complexBuildingMetadataStep",
                repository,
                transactionManager,
                new BuildingMetadataCollectTasklet(service, executionLock, properties.dailyRequestQuota()));
    }

    @Bean
    @Lazy
    Step complexBuildingRatioProjectStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            BuildingRatioProjectionService service,
            BuildingMetadataExecutionLock executionLock) {
        return step(
                "complexBuildingRatioProjectStep",
                repository,
                transactionManager,
                new BuildingRatioProjectTasklet(service, executionLock));
    }

    @Bean
    @Lazy
    Step complexBuildingRegisterCollectStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            BuildingRegisterCampaignService service,
            BuildingMetadataExecutionLock executionLock,
            ComplexMetadataProperties properties) {
        return step(
                "complexBuildingRegisterCollectStep",
                repository,
                transactionManager,
                new BuildingRegisterCollectTasklet(service, executionLock, properties.dailyRequestQuota()));
    }

    private Step step(
            String name,
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            org.springframework.batch.core.step.tasklet.Tasklet tasklet) {
        DefaultTransactionAttribute attribute = new DefaultTransactionAttribute();
        attribute.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return new StepBuilder(name, repository)
                .tasklet(tasklet, transactionManager)
                .transactionAttribute(attribute)
                .build();
    }
}
