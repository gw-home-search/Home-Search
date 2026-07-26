package com.home.batch.metadata;

import com.home.application.ingest.buildingmetadata.BuildingMetadataBatchService;
import com.home.application.ingest.buildingprofile.BuildingProfileAnalysisService;
import com.home.application.ingest.buildingprofile.BuildingProfileCollectionService;
import com.home.application.ingest.buildingprofile.BuildingProfileProjectionService;
import com.home.application.ingest.buildingprofile.BuildingProfileRepairService;
import com.home.application.ingest.buildingprofile.BuildingProfileReplayService;
import com.home.application.ingest.buildingprofile.LegalDongCodeImportService;
import com.home.application.ingest.buildingregister.BuildingRatioProjectionService;
import com.home.application.ingest.buildingregister.BuildingRegisterCampaignService;
import com.home.application.ingest.buildingregister.BuildingRegisterDailyRequestUsage;
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
    Job complexBuildingRegisterProfileReplayJob(
            JobRepository repository, Step complexBuildingRegisterProfileReplayStep) {
        return new JobBuilder("complexBuildingRegisterProfileReplayJob", repository)
                .start(complexBuildingRegisterProfileReplayStep)
                .build();
    }

    @Bean
    @Lazy
    Job complexBuildingRegisterProfileCollectJob(
            JobRepository repository, Step complexBuildingRegisterProfileCollectStep) {
        return new JobBuilder("complexBuildingRegisterProfileCollectJob", repository)
                .start(complexBuildingRegisterProfileCollectStep)
                .build();
    }

    @Bean
    @Lazy
    Job complexBuildingRegisterProfileAnalyzeJob(
            JobRepository repository, Step complexBuildingRegisterProfileAnalyzeStep) {
        return new JobBuilder("complexBuildingRegisterProfileAnalyzeJob", repository)
                .start(complexBuildingRegisterProfileAnalyzeStep)
                .build();
    }

    @Bean
    @Lazy
    Job complexBuildingRegisterProfileProjectJob(
            JobRepository repository, Step complexBuildingRegisterProfileProjectStep) {
        return new JobBuilder("complexBuildingRegisterProfileProjectJob", repository)
                .start(complexBuildingRegisterProfileProjectStep)
                .build();
    }

    @Bean
    @Lazy
    Job complexBuildingRegisterProfileRepairJob(
            JobRepository repository, Step complexBuildingRegisterProfileRepairStep) {
        return new JobBuilder("complexBuildingRegisterProfileRepairJob", repository)
                .start(complexBuildingRegisterProfileRepairStep)
                .build();
    }

    @Bean
    @Lazy
    Job legalDongCodeMappingImportJob(JobRepository repository, Step legalDongCodeMappingImportStep) {
        return new JobBuilder("legalDongCodeMappingImportJob", repository)
                .start(legalDongCodeMappingImportStep)
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
            BuildingRegisterDailyRequestUsage requestUsage,
            ComplexMetadataProperties properties) {
        return step(
                "complexBuildingRegisterCollectStep",
                repository,
                transactionManager,
                new BuildingRegisterCollectTasklet(
                        service, executionLock, requestUsage, properties.dailyRequestQuota()));
    }

    @Bean
    @Lazy
    Step complexBuildingRegisterProfileReplayStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            BuildingProfileReplayService service,
            BuildingMetadataExecutionLock executionLock) {
        return step(
                "complexBuildingRegisterProfileReplayStep",
                repository,
                transactionManager,
                new BuildingProfileReplayTasklet(service, executionLock));
    }

    @Bean
    @Lazy
    Step complexBuildingRegisterProfileCollectStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            BuildingProfileCollectionService service,
            BuildingMetadataExecutionLock executionLock,
            BuildingRegisterDailyRequestUsage requestUsage,
            ComplexMetadataProperties properties) {
        return step(
                "complexBuildingRegisterProfileCollectStep",
                repository,
                transactionManager,
                new BuildingProfileCollectTasklet(
                        service, executionLock, requestUsage, properties.dailyRequestQuota()));
    }

    @Bean
    @Lazy
    Step complexBuildingRegisterProfileAnalyzeStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            BuildingProfileAnalysisService service,
            BuildingMetadataExecutionLock executionLock) {
        return step(
                "complexBuildingRegisterProfileAnalyzeStep",
                repository,
                transactionManager,
                new BuildingProfileAnalyzeTasklet(service, executionLock));
    }

    @Bean
    @Lazy
    Step complexBuildingRegisterProfileProjectStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            BuildingProfileProjectionService service,
            BuildingMetadataExecutionLock executionLock) {
        return step(
                "complexBuildingRegisterProfileProjectStep",
                repository,
                transactionManager,
                new BuildingProfileProjectTasklet(service, executionLock));
    }

    @Bean
    @Lazy
    Step complexBuildingRegisterProfileRepairStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            BuildingProfileRepairService service,
            BuildingMetadataExecutionLock executionLock,
            BuildingRegisterDailyRequestUsage requestUsage,
            ComplexMetadataProperties properties) {
        return step(
                "complexBuildingRegisterProfileRepairStep",
                repository,
                transactionManager,
                new BuildingProfileRepairTasklet(service, executionLock, requestUsage, properties.dailyRequestQuota()));
    }

    @Bean
    @Lazy
    Step legalDongCodeMappingImportStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            LegalDongCodeImportService service,
            BuildingMetadataExecutionLock executionLock) {
        return step(
                "legalDongCodeMappingImportStep",
                repository,
                transactionManager,
                new LegalDongCodeImportTasklet(service, executionLock));
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
