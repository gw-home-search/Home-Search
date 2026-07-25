package com.home.infrastructure.persistence.insight;

import com.home.application.insight.generation.MarketInsightBuildRepository;
import com.home.application.insight.generation.MarketInsightSourceExecution;
import com.home.domain.insight.MarketInsightRejectionReason;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMarketInsightBuildRepository implements MarketInsightBuildRepository {

    private final JdbcInsightExecutionReader executionReader;
    private final JdbcMarketInsightSnapshotWriter snapshotWriter;
    private final JdbcDailyTradeInsightCalculator dailyCalculator;
    private final JdbcExactAreaInsightCalculator exactAreaCalculator;
    private final JdbcCancellationInsightCalculator cancellationCalculator;
    private final JdbcWeeklyTradeInsightCalculator weeklyCalculator;
    private final JdbcRolling7dTradeInsightCalculator rollingCalculator;
    private final JdbcInsightOutboxWriter outboxWriter;

    @Autowired
    public JdbcMarketInsightBuildRepository(
            JdbcInsightExecutionReader executionReader,
            JdbcMarketInsightSnapshotWriter snapshotWriter,
            JdbcDailyTradeInsightCalculator dailyCalculator,
            JdbcExactAreaInsightCalculator exactAreaCalculator,
            JdbcCancellationInsightCalculator cancellationCalculator,
            JdbcWeeklyTradeInsightCalculator weeklyCalculator,
            JdbcRolling7dTradeInsightCalculator rollingCalculator,
            JdbcClient jdbcClient) {
        this.executionReader = Objects.requireNonNull(executionReader);
        this.snapshotWriter = Objects.requireNonNull(snapshotWriter);
        this.dailyCalculator = Objects.requireNonNull(dailyCalculator);
        this.exactAreaCalculator = Objects.requireNonNull(exactAreaCalculator);
        this.cancellationCalculator = Objects.requireNonNull(cancellationCalculator);
        this.weeklyCalculator = Objects.requireNonNull(weeklyCalculator);
        this.rollingCalculator = Objects.requireNonNull(rollingCalculator);
        this.outboxWriter = new JdbcInsightOutboxWriter(Objects.requireNonNull(jdbcClient));
    }

    JdbcMarketInsightBuildRepository(JdbcClient jdbcClient) {
        this(
                new JdbcInsightExecutionReader(jdbcClient),
                new JdbcMarketInsightSnapshotWriter(jdbcClient),
                new JdbcDailyTradeInsightCalculator(jdbcClient),
                new JdbcExactAreaInsightCalculator(jdbcClient),
                new JdbcCancellationInsightCalculator(jdbcClient),
                new JdbcWeeklyTradeInsightCalculator(jdbcClient),
                new JdbcRolling7dTradeInsightCalculator(jdbcClient),
                jdbcClient);
    }

    @Override
    public Optional<MarketInsightSourceExecution> findLatestDailyNationwide(LocalDate runDate) {
        return executionReader.findLatestDailyNationwide(runDate);
    }

    @Override
    public UUID publishDailyNationwide(MarketInsightSourceExecution source, Instant generatedAt) {
        Optional<UUID> published = snapshotWriter.findPublishedDailyNationwide(source.runDate());
        if (published.isPresent()) {
            return published.get();
        }

        JdbcMarketInsightSnapshotWriter.SnapshotSet snapshots = snapshotWriter.createDailyScopes(source, generatedAt);
        dailyCalculator.insertItems(source.executionId());
        exactAreaCalculator.insertItems(source.executionId());
        cancellationCalculator.insertItems(source.executionId());
        snapshotWriter.publish(snapshots);
        outboxWriter.writePublished(source.executionId(), "DAILY", "DAILY", snapshots.scopeCount(), generatedAt);
        return snapshots.nationwideSnapshotId();
    }

    @Override
    public UUID rejectDailyNationwide(
            LocalDate runDate,
            MarketInsightSourceExecution source,
            MarketInsightRejectionReason reason,
            Instant generatedAt) {
        return snapshotWriter.rejectDailyNationwide(runDate, source, reason, generatedAt);
    }

    @Override
    public List<MarketInsightSourceExecution> findLatestDailyNationwideForWeek(LocalDate weekStart) {
        return executionReader.findLatestDailyNationwideForWeek(weekStart);
    }

    @Override
    public UUID publishWeeklyNationwide(
            LocalDate weekStart, List<MarketInsightSourceExecution> sources, Instant generatedAt) {
        Optional<UUID> published = snapshotWriter.findPublishedWeeklyNationwide(weekStart);
        if (published.isPresent()) return published.get();
        var snapshots = snapshotWriter.createWeeklyScopes(weekStart, sources, generatedAt);
        weeklyCalculator.insertItems(weekStart);
        snapshotWriter.publishWeekly(snapshots);
        outboxWriter.writeWeeklyPublished(
                weekStart,
                snapshots.nationwideSnapshotId(),
                snapshots.snapshotIds().size(),
                generatedAt);
        return snapshots.nationwideSnapshotId();
    }

    @Override
    public UUID rejectWeeklyNationwide(
            LocalDate weekStart,
            List<MarketInsightSourceExecution> sources,
            MarketInsightRejectionReason reason,
            Instant generatedAt) {
        return snapshotWriter.rejectWeeklyNationwide(weekStart, sources, reason, generatedAt);
    }

    @Override
    public UUID publishRolling7dNationwide(MarketInsightSourceExecution source, Instant generatedAt) {
        Optional<JdbcMarketInsightSnapshotWriter.PublishedRollingSnapshot> published =
                snapshotWriter.findPublishedRollingNationwide(source.runDate());
        if (published.isPresent() && published.get().executionId().equals(source.executionId())) {
            return published.get().snapshotId();
        }
        JdbcMarketInsightSnapshotWriter.RollingSnapshotSet snapshots =
                snapshotWriter.createRollingScopes(source, generatedAt);
        rollingCalculator.insertItems(source.executionId());
        snapshotWriter.supersedeAndPublishRolling(snapshots);
        outboxWriter.writePublished(
                source.executionId(),
                "ROLLING_7D",
                "ROLLING_7D",
                snapshots.snapshotIds().size(),
                generatedAt);
        return snapshots.nationwideSnapshotId();
    }

    @Override
    public UUID rejectRolling7dNationwide(
            LocalDate runDate,
            MarketInsightSourceExecution source,
            MarketInsightRejectionReason reason,
            Instant generatedAt) {
        return snapshotWriter.rejectRollingNationwide(runDate, source, reason, generatedAt);
    }
}
