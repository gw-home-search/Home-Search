package com.home.batch.launch;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class BatchJobLauncherApplicationRunner implements ApplicationRunner {

    private final Supplier<JobOperator> jobOperator;
    private final Function<String, Job> jobLookup;
    private final Predicate<String> jobExists;
    private final BatchMetadataSchemaPreflight schemaPreflight;
    private final BatchExecutionCorrelationGuard correlationGuard;
    private final Environment environment;
    private final Clock clock;
    private final BatchExitCodeExceptionMapper exitCodes;

    @Autowired
    public BatchJobLauncherApplicationRunner(
            ObjectProvider<JobOperator> jobOperator,
            ConfigurableListableBeanFactory beanFactory,
            BatchMetadataSchemaPreflight schemaPreflight,
            BatchExecutionCorrelationGuard correlationGuard,
            Environment environment) {
        this(
                jobOperator::getObject,
                name -> beanFactory.getBean(name, Job.class),
                beanFactory::containsBeanDefinition,
                schemaPreflight,
                correlationGuard,
                environment,
                Clock.systemUTC(),
                new BatchExitCodeExceptionMapper());
    }

    BatchJobLauncherApplicationRunner(
            JobOperator jobOperator,
            List<Job> jobs,
            BatchMetadataSchemaPreflight schemaPreflight,
            BatchExecutionCorrelationGuard correlationGuard,
            Environment environment,
            Clock clock,
            BatchExitCodeExceptionMapper exitCodes) {
        this(
                () -> jobOperator,
                jobsByName(jobs)::get,
                jobsByName(jobs)::containsKey,
                schemaPreflight,
                correlationGuard,
                environment,
                clock,
                exitCodes);
    }

    private BatchJobLauncherApplicationRunner(
            Supplier<JobOperator> jobOperator,
            Function<String, Job> jobLookup,
            Predicate<String> jobExists,
            BatchMetadataSchemaPreflight schemaPreflight,
            BatchExecutionCorrelationGuard correlationGuard,
            Environment environment,
            Clock clock,
            BatchExitCodeExceptionMapper exitCodes) {
        this.jobOperator = Objects.requireNonNull(jobOperator);
        this.jobLookup = Objects.requireNonNull(jobLookup);
        this.jobExists = Objects.requireNonNull(jobExists);
        this.schemaPreflight = Objects.requireNonNull(schemaPreflight);
        this.correlationGuard = Objects.requireNonNull(correlationGuard);
        this.environment = Objects.requireNonNull(environment);
        this.clock = Objects.requireNonNull(clock);
        this.exitCodes = Objects.requireNonNull(exitCodes);
    }

    private static Map<String, Job> jobsByName(List<Job> jobs) {
        return jobs.stream().collect(Collectors.toUnmodifiableMap(Job::getName, Function.identity()));
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        BatchJobArguments parsed =
                BatchJobArguments.from(environment.getProperty("SPRING_BATCH_JOB_NAME"), args, clock);
        if (!jobExists.test(parsed.jobName())) {
            throw exitCodes.invalidArgument("Unknown batch job: " + parsed.jobName());
        }
        schemaPreflight.verify();
        try (BatchExecutionCorrelationGuard.Lock ignored =
                correlationGuard.lock(parsed.jobParameters().getString("requestId"))) {
            correlationGuard.verify(parsed.jobName(), parsed.jobParameters());
            Job job = jobLookup.apply(parsed.jobName());
            JobExecution execution = jobOperator.get().start(job, parsed.jobParameters());
            ExitStatus exitStatus = execution.getExitStatus();
            if (!exitCodes.successful(exitStatus)) {
                throw exitCodes.failedJob("Batch job ended with exit status: " + exitStatus.getExitCode());
            }
        }
    }
}
