package com.home.sourcedata.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

@Component
public final class SourceDataMigrationRunner implements ApplicationRunner, ExitCodeGenerator {
    static final String EXPECTED_DATABASE = "home_search_coordinate_source";

    private final DataSource dataSource;
    private int exitCode;

    SourceDataMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            Map<String, String> options = parse(arguments.getSourceArgs());
            String operation = required(options, "operation");
            String database = databaseName();
            if (!EXPECTED_DATABASE.equals(database)) {
                throw new UsageException("target database must be " + EXPECTED_DATABASE);
            }
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/coordinate-source")
                    .schemas("reference", "public", "geo_enrichment")
                    .defaultSchema("reference")
                    .table("flyway_schema_history")
                    .load();
            switch (operation) {
                case "info" -> flyway.info();
                case "validate" -> flyway.validate();
                case "migrate" -> migrate(flyway, options);
                case "preflight-baseline" -> preflightBaseline();
                case "baseline-existing" -> baseline(flyway, options, database);
                default -> throw new UsageException("unsupported operation: " + operation);
            }
        } catch (UsageException | LegacyCoordinateSourceFingerprint.LegacyFingerprintMismatchException exception) {
            System.err.println(exception.getMessage());
            exitCode = 2;
        } catch (Exception exception) {
            System.err.println("source-data migration failed: " + exception.getMessage());
            exitCode = 1;
        }
    }

    private void migrate(Flyway flyway, Map<String, String> options) {
        String target = required(options, "target");
        if (!target.equals(required(options, "confirm"))) {
            throw new UsageException("--confirm must equal --target");
        }
        Flyway configured = Flyway.configure()
                .configuration(flyway.getConfiguration())
                .target(target)
                .load();
        Flyway.configure()
                .configuration(configured.getConfiguration())
                .ignoreMigrationPatterns("*:pending")
                .load()
                .validate();
        configured.migrate();
        configured.validate();
    }

    private void baseline(Flyway flyway, Map<String, String> options, String database) {
        if (!database.equals(required(options, "confirm-database"))) {
            throw new UsageException("--confirm-database must equal the connected database");
        }
        LegacyCoordinateSourceFingerprint.LegacyFingerprintEvidence evidence = preflightBaseline();
        Flyway.configure()
                .configuration(flyway.getConfiguration())
                .baselineVersion("1")
                .baselineDescription("controlled legacy coordinate source adoption")
                .load()
                .baseline();
        System.out.println(
                "legacy coordinate source baseline completed: version=1, estimatedRows=" + evidence.estimatedRows());
    }

    private LegacyCoordinateSourceFingerprint.LegacyFingerprintEvidence preflightBaseline() {
        LegacyCoordinateSourceFingerprint.LegacyFingerprintEvidence evidence;
        try {
            evidence = new LegacyCoordinateSourceFingerprint().verify(dataSource);
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("legacy fingerprint query failed", exception);
        }
        System.out.println("legacy coordinate source fingerprint passed: estimatedRows=" + evidence.estimatedRows());
        return evidence;
    }

    private String databaseName() throws Exception {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement("select current_database()");
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getString(1);
        }
    }

    static Map<String, String> parse(String[] args) {
        return Arrays.stream(args)
                .map(value -> value.split("=", 2))
                .filter(parts -> parts.length == 2 && parts[0].startsWith("--"))
                .collect(Collectors.toUnmodifiableMap(
                        parts -> parts[0].substring(2), parts -> parts[1], (left, right) -> {
                            throw new UsageException("duplicate option");
                        }));
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new UsageException("missing --" + key);
        return value;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }
}
