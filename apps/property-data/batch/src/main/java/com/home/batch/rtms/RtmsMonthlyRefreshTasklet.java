package com.home.batch.rtms;

import com.home.application.ingest.rtms.RtmsMonthlyRefreshRunSummary;
import com.home.application.ingest.rtms.RtmsMonthlyRefreshUseCase;
import com.home.domain.ingest.run.ExecutionCorrelationId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

public class RtmsMonthlyRefreshTasklet implements Tasklet {

    static final String WORKSET_CONTEXT_KEY = "rtmsRefreshWorkset";
    static final ExitStatus COMPLETED_WITH_WARNINGS = new ExitStatus("COMPLETED_WITH_WARNINGS");

    private final RtmsMonthlyRefreshUseCase useCase;
    private final RtmsRefreshWorksetPlanner planner;
    private final String configuredDailyLawdCds;
    private final int dailyLookbackMonths;
    private final boolean daily;

    public RtmsMonthlyRefreshTasklet(
            RtmsMonthlyRefreshUseCase useCase,
            RtmsRefreshWorksetPlanner planner,
            String configuredDailyLawdCds,
            int dailyLookbackMonths,
            boolean daily) {
        this.useCase = useCase;
        this.planner = planner;
        this.configuredDailyLawdCds = configuredDailyLawdCds;
        this.dailyLookbackMonths = dailyLookbackMonths;
        this.daily = daily;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        ExecutionContext jobContext = chunkContext
                .getStepContext()
                .getStepExecution()
                .getJobExecution()
                .getExecutionContext();
        List<RtmsRefreshWorkUnit> workset =
                workset(jobContext, contribution.getStepExecution().getJobParameters());
        ExecutionCorrelationId correlationId = ExecutionCorrelationId.from(
                contribution.getStepExecution().getJobParameters().getString("requestId"));
        List<RtmsMonthlyRefreshRunSummary> runs = new ArrayList<>();
        for (RtmsRefreshWorkUnit unit : workset) {
            runs.add(useCase.refresh(unit.lawdCd(), unit.dealYmd(), correlationId));
        }
        RtmsBatchExecutionSummary summary = new RtmsBatchExecutionSummary(runs, false);
        jobContext.put(RtmsBatchExecutionSummary.WARNINGS_CONTEXT_KEY, summary.hasWarnings());
        if (summary.hasWarnings()) {
            contribution.setExitStatus(COMPLETED_WITH_WARNINGS);
        }
        return RepeatStatus.FINISHED;
    }

    private List<RtmsRefreshWorkUnit> workset(ExecutionContext jobContext, JobParameters parameters) {
        if (jobContext.containsKey(WORKSET_CONTEXT_KEY)) {
            return Arrays.stream(jobContext.getString(WORKSET_CONTEXT_KEY).split(";"))
                    .filter(value -> !value.isBlank())
                    .map(RtmsRefreshWorkUnit::parse)
                    .toList();
        }
        List<RtmsRefreshWorkUnit> planned = daily ? dailyWorkset(parameters) : backfillWorkset(parameters);
        jobContext.putString(WORKSET_CONTEXT_KEY, serialize(planned));
        return planned;
    }

    private List<RtmsRefreshWorkUnit> dailyWorkset(JobParameters parameters) {
        LocalDate runDate = LocalDate.parse(parameters.getString("runDate"));
        return planner.daily(runDate, configuredDailyLawdCds, dailyLookbackMonths);
    }

    private List<RtmsRefreshWorkUnit> backfillWorkset(JobParameters parameters) {
        return planner.backfill(
                parameters.getString("fromYmd"), parameters.getString("toYmd"), parameters.getString("lawdCds"));
    }

    private static String serialize(List<RtmsRefreshWorkUnit> workset) {
        return String.join(
                ";", workset.stream().map(RtmsRefreshWorkUnit::serialized).toList());
    }
}
