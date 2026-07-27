package com.home.infrastructure.persistence.ingest.matching;

import com.home.application.ingest.buildingprofile.BuildingProfilePublicationCommand;
import com.home.application.ingest.buildingprofile.BuildingProfilePublicationRepository;
import com.home.application.ingest.buildingprofile.BuildingProfilePublicationSummary;
import com.home.domain.complex.buildingprofile.BuildingProfileEffectiveValuePolicy;
import com.home.domain.complex.buildingprofile.BuildingProfileField;
import com.home.domain.complex.buildingprofile.BuildingProfilePublicationStatus;
import com.home.domain.complex.buildingprofile.BuildingProfileRatioCalculator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcBuildingProfilePublicationRepository implements BuildingProfilePublicationRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final ObjectFactory<DataSource> dataSources;
    private final BuildingProfileRatioCalculator ratioCalculator = new BuildingProfileRatioCalculator();

    @Autowired
    public JdbcBuildingProfilePublicationRepository(
            JdbcClient jdbc, TransactionTemplate transaction, ObjectFactory<DataSource> dataSources) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
        this.dataSources = Objects.requireNonNull(dataSources);
    }

    public JdbcBuildingProfilePublicationRepository(
            JdbcClient jdbc, TransactionTemplate transaction, DataSource dataSource) {
        this(jdbc, transaction, () -> Objects.requireNonNull(dataSource));
    }

    @Override
    public BuildingProfilePublicationSummary publish(BuildingProfilePublicationCommand command) {
        return transaction.execute(ignored -> publishInTransaction(command));
    }

    private BuildingProfilePublicationSummary publishInTransaction(BuildingProfilePublicationCommand command) {
        ExistingPublication existing = existing(command.publicationId());
        if (existing != null) {
            if (!existing.projectionRunId().equals(command.projectionRunId())
                    || !existing.rulesVersion().equals(command.rulesVersion())) {
                throw new IllegalArgumentException("publicationId is already frozen with different inputs");
            }
            if (existing.status() == BuildingProfilePublicationStatus.VALIDATED
                    || existing.status() == BuildingProfilePublicationStatus.PUBLISHED) {
                if (command.publish() && existing.status() == BuildingProfilePublicationStatus.VALIDATED) {
                    publishAndBackfill(command);
                    return summary(command.publicationId(), false);
                }
                if (command.backfill() && existing.status() == BuildingProfilePublicationStatus.PUBLISHED) {
                    backfill(command.publicationId());
                }
                return summary(command.publicationId(), true);
            }
            throw new IllegalStateException("profile publication cannot resume from " + existing.status());
        }

        SourceCounts counts = sourceCounts(command.projectionRunId());
        if (!counts.completed()) {
            throw new IllegalStateException("completed parse, analysis, and projection runs are required");
        }
        if (counts.valueCount() != counts.expectedValueCount() || counts.invalidFieldCount() != 0) {
            throw new IllegalStateException("83-field source value set is incomplete");
        }
        if (counts.duplicateBuildingKeyCount() != 0 || counts.duplicateRootKeyCount() != 0) {
            throw new IllegalStateException("conflicting management keys cannot be published");
        }

        insertPublication(command, counts);
        update(JdbcBuildingProfilePublicationSql.SITE, command);
        update(JdbcBuildingProfilePublicationSql.BUILDING, command);
        update(JdbcBuildingProfilePublicationSql.HIERARCHY, command);
        update(JdbcBuildingProfilePublicationSql.EVIDENCE, command);
        update(JdbcBuildingProfilePublicationSql.EVIDENCE_CLASSIFICATION, command);
        update(JdbcBuildingProfilePublicationSql.SUMMARY, command);
        fillCalculatedRatios(command.publicationId());
        update(JdbcBuildingProfilePublicationSql.AGGREGATE_CONFLICTS, command);

        String digest = contentDigest(command.publicationId());
        jdbc.sql("SELECT validate_building_register_profile(:publication,:digest)")
                .param("publication", command.publicationId())
                .param("digest", digest)
                .query(Object.class)
                .optional();
        if (command.publish()) {
            publishAndBackfill(command);
        }
        return summary(command.publicationId(), false);
    }

    private void publishAndBackfill(BuildingProfilePublicationCommand command) {
        jdbc.sql("SELECT publish_building_register_profile(:publication)")
                .param("publication", command.publicationId())
                .query(Object.class)
                .optional();
        if (command.backfill()) {
            backfill(command.publicationId());
        }
    }

    private void backfill(UUID publicationId) {
        jdbc.sql("SELECT backfill_building_register_profile_operational_columns(:publication)")
                .param("publication", publicationId)
                .query(Object.class)
                .optional();
    }

    private void fillCalculatedRatios(UUID publicationId) {
        List<CalculatedRatioUpdate> updates = jdbc.sql("""
                    SELECT complex_id,site_area_m2,building_area_m2,floor_area_ratio_area_m2,
                           building_coverage_rate,floor_area_ratio
                    FROM complex_building_register_profile_summary
                    WHERE publication_id=:publication AND site_area_m2>0
                      AND building_area_m2>0 AND floor_area_ratio_area_m2>0
                    ORDER BY complex_id
                    """)
                .param("publication", publicationId)
                .query((rs, rowNum) -> {
                    var ratios = ratioCalculator.calculateFromCompleteTotals(
                            rs.getBigDecimal("site_area_m2"),
                            rs.getBigDecimal("building_area_m2"),
                            rs.getBigDecimal("floor_area_ratio_area_m2"));
                    return new CalculatedRatioUpdate(
                            rs.getLong("complex_id"),
                            rs.getBigDecimal("building_coverage_rate"),
                            rs.getBigDecimal("floor_area_ratio"),
                            ratios.buildingCoverageRatio(),
                            ratios.floorAreaRatio());
                })
                .list();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        jdbcTemplate.batchUpdate("""
                UPDATE complex_building_register_profile_summary
                SET building_coverage_rate=coalesce(building_coverage_rate,?),
                    floor_area_ratio=coalesce(floor_area_ratio,?)
                WHERE publication_id=? AND complex_id=?
                """, updates, 1_000, (statement, update) -> {
            statement.setBigDecimal(1, update.buildingCoverageRate());
            statement.setBigDecimal(2, update.floorAreaRatio());
            statement.setObject(3, publicationId);
            statement.setLong(4, update.complexId());
        });
        List<RatioConflict> conflicts =
                updates.stream().flatMap(update -> update.conflicts().stream()).toList();
        jdbcTemplate.batchUpdate("""
                UPDATE building_register_profile_field_evidence evidence
                SET conflict_status='AGGREGATE_CONFLICT'
                FROM building_register_profile_publication publication,
                     complex_building_register_profile projected,
                     building_register_profile_hierarchy hierarchy
                WHERE publication.publication_id=?
                  AND projected.projection_run_id=publication.source_projection_run_id
                  AND projected.complex_id=? AND projected.source_root_management_key IS NOT NULL
                  AND hierarchy.publication_id=publication.publication_id
                  AND hierarchy.mgm_bldrgst_pk=projected.source_root_management_key
                  AND evidence.publication_id=publication.publication_id
                  AND evidence.source_record_key=hierarchy.source_record_key
                  AND evidence.field_id=? AND evidence.conflict_status='NONE'
                """, conflicts, 1_000, (statement, conflict) -> {
            statement.setObject(1, publicationId);
            statement.setLong(2, conflict.complexId());
            statement.setString(3, conflict.fieldId());
        });
    }

    private int update(String sql, BuildingProfilePublicationCommand command) {
        return jdbc.sql(sql)
                .param("publication", command.publicationId())
                .param("projection", command.projectionRunId())
                .update();
    }

    private void insertPublication(BuildingProfilePublicationCommand command, SourceCounts counts) {
        jdbc.sql("""
                    INSERT INTO building_register_profile_publication(
                      publication_id,source_collection_id,source_parse_run_id,source_analysis_run_id,
                      source_projection_run_id,rules_version,parser_version,status,
                      expected_site_count,expected_building_count,expected_hierarchy_count,
                      expected_evidence_count,expected_summary_count)
                    SELECT :publication,projection.collection_id,projection.parse_run_id,
                           projection.analysis_run_id,projection.projection_run_id,:rules,
                           parse.parser_version,'PREPARING',:sites,:buildings,:hierarchy,:evidence,:summaries
                    FROM building_register_profile_projection_run projection
                    JOIN building_register_profile_parse_run parse ON parse.parse_run_id=projection.parse_run_id
                    WHERE projection.projection_run_id=:projection
                    """)
                .param("publication", command.publicationId())
                .param("projection", command.projectionRunId())
                .param("rules", command.rulesVersion())
                .param("sites", counts.siteCount())
                .param("buildings", counts.buildingCount())
                .param("hierarchy", counts.hierarchyCount())
                .param("evidence", counts.valueCount())
                .param("summaries", counts.summaryCount())
                .update();
    }

    private SourceCounts sourceCounts(UUID projectionRunId) {
        List<String> allFields = java.util.Arrays.stream(BuildingProfileField.values())
                .map(Enum::name)
                .toList();
        List<String> basicFields = java.util.Arrays.stream(BuildingProfileField.values())
                .filter(BuildingProfileField::hierarchyLeanField)
                .map(Enum::name)
                .toList();
        return jdbc.sql(JdbcBuildingProfilePublicationSql.SOURCE_COUNTS)
                .param("projection", projectionRunId)
                .param("allFields", allFields)
                .param("basicFields", basicFields)
                .query((rs, rowNum) -> new SourceCounts(
                        rs.getBoolean("completed"),
                        rs.getInt("site_count"),
                        rs.getInt("building_count"),
                        rs.getInt("hierarchy_count"),
                        rs.getInt("value_count"),
                        rs.getInt("invalid_field_count"),
                        rs.getInt("expected_value_count"),
                        rs.getInt("summary_count"),
                        rs.getInt("duplicate_building_key_count"),
                        rs.getInt("duplicate_root_key_count")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("projectionRunId does not exist"));
    }

    private ExistingPublication existing(UUID publicationId) {
        return jdbc.sql("""
                    SELECT source_projection_run_id,rules_version,status
                    FROM building_register_profile_publication
                    WHERE publication_id=:publication FOR UPDATE
                    """)
                .param("publication", publicationId)
                .query((rs, rowNum) -> new ExistingPublication(
                        rs.getObject("source_projection_run_id", UUID.class),
                        rs.getString("rules_version"),
                        BuildingProfilePublicationStatus.valueOf(rs.getString("status"))))
                .optional()
                .orElse(null);
    }

    private String contentDigest(UUID publicationId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DataSource dataSource = dataSource();
            var connection = DataSourceUtils.getConnection(dataSource);
            try (var statement = connection.prepareStatement(JdbcBuildingProfilePublicationSql.CONTENT_KEYS)) {
                statement.setObject(1, publicationId);
                statement.setFetchSize(1_000);
                try (var lines = statement.executeQuery()) {
                    while (lines.next()) {
                        digest.update(lines.getString(1).getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) '\n');
                    }
                }
            } catch (SQLException exception) {
                throw new DataAccessResourceFailureException("failed to stream profile publication digest", exception);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private DataSource dataSource() {
        return dataSources.getObject();
    }

    private BuildingProfilePublicationSummary summary(UUID publicationId, boolean alreadyCompleted) {
        return jdbc.sql("""
                    SELECT site_count,building_count,hierarchy_count,evidence_count,summary_count,
                           content_sha256,status
                    FROM building_register_profile_publication WHERE publication_id=:publication
                    """)
                .param("publication", publicationId)
                .query((rs, rowNum) -> new BuildingProfilePublicationSummary(
                        rs.getInt("site_count"),
                        rs.getInt("building_count"),
                        rs.getInt("hierarchy_count"),
                        rs.getInt("evidence_count"),
                        rs.getInt("summary_count"),
                        rs.getString("content_sha256"),
                        BuildingProfilePublicationStatus.valueOf(rs.getString("status")),
                        alreadyCompleted))
                .single();
    }

    private record ExistingPublication(
            UUID projectionRunId, String rulesVersion, BuildingProfilePublicationStatus status) {}

    private record SourceCounts(
            boolean completed,
            int siteCount,
            int buildingCount,
            int hierarchyCount,
            int valueCount,
            int invalidFieldCount,
            int expectedValueCount,
            int summaryCount,
            int duplicateBuildingKeyCount,
            int duplicateRootKeyCount) {}

    private record CalculatedRatioUpdate(
            long complexId,
            BigDecimal buildingCoverageRate,
            BigDecimal floorAreaRatio,
            BigDecimal directBuildingCoverageRate,
            BigDecimal directFloorAreaRatio,
            BigDecimal calculatedBuildingCoverageRate,
            BigDecimal calculatedFloorAreaRatio) {
        private CalculatedRatioUpdate(
                long complexId,
                BigDecimal directBc,
                BigDecimal directVl,
                BigDecimal calculatedBc,
                BigDecimal calculatedVl) {
            this(
                    complexId,
                    directBc == null ? calculatedBc : null,
                    directVl == null ? calculatedVl : null,
                    directBc,
                    directVl,
                    calculatedBc,
                    calculatedVl);
        }

        List<RatioConflict> conflicts() {
            var conflicts = new java.util.ArrayList<RatioConflict>(2);
            if (differs(directBuildingCoverageRate, calculatedBuildingCoverageRate)) {
                conflicts.add(new RatioConflict(complexId, "BC_RAT"));
            }
            if (differs(directFloorAreaRatio, calculatedFloorAreaRatio)) {
                conflicts.add(new RatioConflict(complexId, "VL_RAT"));
            }
            return List.copyOf(conflicts);
        }

        private boolean differs(BigDecimal direct, BigDecimal calculated) {
            return direct != null
                    && calculated != null
                    && direct.subtract(calculated).abs().compareTo(BuildingProfileEffectiveValuePolicy.RATIO_TOLERANCE)
                            > 0;
        }
    }

    private record RatioConflict(long complexId, String fieldId) {}
}
