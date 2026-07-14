package com.home.sourcedata.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class SourceDataFlywayIntegrationTest {
    @Test
    void migrationRunnerAllowsOnlyPendingMigrationsBeforeStrictPostValidation() throws Exception {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            DriverManagerDataSource dataSource = dataSource(database);
            SourceDataMigrationRunner runner = new SourceDataMigrationRunner(dataSource);

            runner.run(new DefaultApplicationArguments("--operation=migrate", "--target=2", "--confirm=2"));

            assertThat(runner.getExitCode()).isZero();
            assertThat(
                            scalar(
                                    dataSource,
                                    "SELECT count(*) FROM reference.flyway_schema_history WHERE success AND version IN ('1','2')"))
                    .isEqualTo(2);

            SourceDataMigrationRunner infoRunner = new SourceDataMigrationRunner(dataSource);
            infoRunner.run(new DefaultApplicationArguments("--operation=info"));
            assertThat(infoRunner.getExitCode()).isZero();

            SourceDataMigrationRunner latestRunner = new SourceDataMigrationRunner(dataSource);
            latestRunner.run(new DefaultApplicationArguments("--operation=migrate", "--target=4", "--confirm=4"));
            assertThat(latestRunner.getExitCode()).isZero();
            SourceDataMigrationRunner validateRunner = new SourceDataMigrationRunner(dataSource);
            validateRunner.run(new DefaultApplicationArguments("--operation=validate"));
            assertThat(validateRunner.getExitCode()).isZero();
        }
    }

    @Test
    void controlledLegacyAdoptionPreservesSnapshotRowsThroughVersionThree() throws Exception {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            DriverManagerDataSource dataSource = dataSource(database);
            createLegacySchema(dataSource);

            SourceDataMigrationRunner runner = new SourceDataMigrationRunner(dataSource);
            runner.run(new DefaultApplicationArguments("--operation=preflight-baseline"));
            assertThat(runner.getExitCode()).isZero();

            LegacyCoordinateSourceFingerprint.LegacyFingerprintEvidence evidence =
                    new LegacyCoordinateSourceFingerprint().verify(dataSource);
            assertThat(evidence.estimatedRows()).containsKeys("coordinate_snapshot_run", "parcel_coordinate_snapshot");

            runner.run(new DefaultApplicationArguments(
                    "--operation=baseline-existing",
                    "--confirm-database=" + SourceDataMigrationRunner.EXPECTED_DATABASE));
            assertThat(runner.getExitCode()).isZero();

            Flyway flyway = flyway(dataSource, "2");
            flyway.migrate();
            flyway.validate();

            assertThat(scalar(dataSource, "SELECT count(*) FROM reference.parcel_coordinate_snapshot"))
                    .isEqualTo(1);
            assertThat(
                            scalar(
                                    dataSource,
                                    "SELECT count(*) FROM reference.flyway_schema_history WHERE version='1' AND type='BASELINE' AND success"))
                    .isEqualTo(1);
            assertThat(scalar(
                            dataSource,
                            "SELECT count(*) FROM reference.flyway_schema_history WHERE version='2' AND success"))
                    .isEqualTo(1);
            assertThat(regclass(dataSource, "reference.coordinate_snapshot_publish_checkpoint"))
                    .isNotNull();
            assertThat(regclass(dataSource, "geo_enrichment.vworld_wfs_footprint_cache"))
                    .isNull();

            Flyway latest = flyway(dataSource, "4");
            latest.migrate();
            latest.validate();
            assertThat(regclass(dataSource, "geo_enrichment.vworld_wfs_footprint_cache"))
                    .isNotNull();
            assertThat(scalar(dataSource, "SELECT count(*) FROM reference.parcel_coordinate_snapshot"))
                    .isEqualTo(1);
        }
    }

    @Test
    void freshDatabaseMigratesFromVersionOneToFour() throws Exception {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            DriverManagerDataSource dataSource = dataSource(database);
            createSourceRoles(dataSource);
            Flyway flyway = flyway(dataSource, "4");

            flyway.migrate();
            flyway.validate();

            assertThat(
                            scalar(
                                    dataSource,
                                    "SELECT count(*) FROM reference.flyway_schema_history WHERE success AND version IS NOT NULL"))
                    .isEqualTo(4);
            assertThat(regclass(dataSource, "reference.parcel_coordinate_snapshot"))
                    .isNotNull();
            assertThat(regclass(dataSource, "reference.parcel_coordinate_snapshot_stage"))
                    .isNotNull();
            assertThat(regclass(dataSource, "geo_enrichment.vworld_wfs_footprint_cache"))
                    .isNotNull();
            DriverManagerDataSource reader = new DriverManagerDataSource(
                    database.getJdbcUrl(), "home_search_coordinate_reader", "reader-test-password");
            assertThat(scalar(reader, "SELECT count(*) FROM reference.parcel_coordinate_snapshot"))
                    .isZero();
            assertThatThrownBy(() -> execute(
                            reader,
                            "INSERT INTO reference.parcel_coordinate_snapshot(pnu) VALUES ('1168010300101400001')"))
                    .isInstanceOf(Exception.class);
            DriverManagerDataSource importer = new DriverManagerDataSource(
                    database.getJdbcUrl(), "home_search_coordinate_importer", "importer-test-password");
            execute(importer, "CREATE TABLE reference.importer_work_table(id integer)");
            assertThatThrownBy(() -> execute(
                            importer, "UPDATE reference.flyway_schema_history SET description=description WHERE false"))
                    .isInstanceOf(Exception.class);
        }
    }

    private PostgreSQLContainer<?> database() {
        DockerImageName image = DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");
        return new PostgreSQLContainer<>(image)
                .withDatabaseName("home_search_coordinate_source")
                .withUsername("source_test")
                .withPassword("source_test");
    }

    private DriverManagerDataSource dataSource(PostgreSQLContainer<?> database) {
        return new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private Flyway flyway(DriverManagerDataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/coordinate-source")
                .schemas("reference", "public", "geo_enrichment")
                .defaultSchema("reference")
                .table("flyway_schema_history")
                .baselineVersion("1")
                .baselineDescription("controlled legacy coordinate source adoption")
                .target(target)
                .load();
    }

    private void createLegacySchema(DriverManagerDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("db/migration/coordinate-source/V1__create_coordinate_source_schema.sql"));
            execute(connection, """
                DROP TABLE reference.parcel_coordinate_snapshot_publish;
                DROP TABLE reference.parcel_coordinate_snapshot_stage;
                DROP TABLE reference.coordinate_snapshot_publish_chunk_checkpoint;
                DROP TABLE reference.coordinate_snapshot_publish_checkpoint;
                DROP TABLE reference.coordinate_snapshot_stage_chunk_checkpoint;
                DROP TABLE reference.coordinate_snapshot_region_checkpoint;
                ALTER TABLE reference.coordinate_snapshot_run DROP CONSTRAINT coordinate_snapshot_run_target_srid_check;
                ALTER TABLE reference.coordinate_snapshot_run ALTER COLUMN source_dataset TYPE varchar(64);
                ALTER TABLE reference.coordinate_snapshot_run ALTER COLUMN source_dataset SET DEFAULT 'VWORLD_CONTINUOUS_CADASTRAL';
                ALTER TABLE reference.coordinate_snapshot_run ALTER COLUMN source_dataset SET NOT NULL;
                ALTER TABLE reference.coordinate_snapshot_run ALTER COLUMN source_dir DROP NOT NULL;
                ALTER TABLE reference.coordinate_snapshot_run ADD CONSTRAINT coordinate_snapshot_run_file_count_check CHECK (file_count >= 0);
                ALTER TABLE reference.coordinate_snapshot_run ADD CONSTRAINT coordinate_snapshot_run_region_count_check CHECK (region_count >= 0);
                ALTER TABLE reference.coordinate_snapshot_run ADD CONSTRAINT coordinate_snapshot_run_raw_feature_count_check CHECK (raw_feature_count >= 0);
                ALTER TABLE reference.coordinate_snapshot_run ADD CONSTRAINT coordinate_snapshot_run_pnu_count_check CHECK (pnu_count >= 0);
                ALTER TABLE reference.coordinate_snapshot_run ADD CONSTRAINT coordinate_snapshot_run_invalid_count_check CHECK (invalid_count >= 0);
                ALTER TABLE reference.coordinate_snapshot_run ADD CONSTRAINT coordinate_snapshot_run_duplicate_pnu_count_check CHECK (duplicate_pnu_count >= 0);
                ALTER TABLE reference.coordinate_snapshot_run ADD CONSTRAINT coordinate_snapshot_run_synced_parcel_count_check CHECK (synced_parcel_count >= 0);
                ALTER TABLE reference.parcel_coordinate_snapshot ALTER COLUMN region_code TYPE varchar(8);
                ALTER TABLE reference.parcel_coordinate_snapshot ALTER COLUMN run_id DROP NOT NULL;
                INSERT INTO reference.coordinate_snapshot_run (snapshot_version, source_dir, source_srid, status) VALUES ('legacy-test', NULL, 5186, 'PASSED');
                INSERT INTO reference.parcel_coordinate_snapshot
                    (pnu, region_code, latitude, longitude, point, geom, snapshot_version, source_file, run_id)
                SELECT '1168010300101400001', '11680', 37.5000000, 127.0000000,
                    ST_SetSRID(ST_Point(127.0, 37.5), 4326),
                    ST_Multi(ST_GeomFromText('POLYGON((127 37.5,127.001 37.5,127.001 37.501,127 37.5))', 4326)),
                    'legacy-test', 'fixture.shp', id
                FROM reference.coordinate_snapshot_run;
                ANALYZE reference.coordinate_snapshot_run;
                ANALYZE reference.parcel_coordinate_snapshot;
                """);
        }
    }

    private void createSourceRoles(DriverManagerDataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            execute(
                    connection,
                    "CREATE ROLE home_search_coordinate_reader LOGIN PASSWORD 'reader-test-password'; CREATE ROLE home_search_coordinate_importer LOGIN PASSWORD 'importer-test-password'");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void execute(DriverManagerDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            execute(connection, sql);
        }
    }

    private long scalar(DriverManagerDataSource dataSource, String sql) {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement(sql);
                var result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String regclass(DriverManagerDataSource dataSource, String relation) {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT to_regclass(?)::text")) {
            statement.setString(1, relation);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
