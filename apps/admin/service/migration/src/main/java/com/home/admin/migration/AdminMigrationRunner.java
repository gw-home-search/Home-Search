package com.home.admin.migration;

import java.sql.Connection;
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
final class AdminMigrationRunner implements ApplicationRunner, ExitCodeGenerator {
    static final String EXPECTED_DATABASE = "home_search_admin";
    private final DataSource dataSource;
    private int exitCode;

    AdminMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            Map<String, String> options = parse(arguments.getSourceArgs());
            String operation = required(options, "operation");
            requireDatabase();
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/admin")
                    .schemas("admin")
                    .defaultSchema("admin")
                    .table("flyway_schema_history")
                    .load();
            switch (operation) {
                case "info" -> flyway.info();
                case "validate" -> flyway.validate();
                case "migrate" -> migrate(flyway, options);
                default -> throw new UsageException("unsupported operation: " + operation);
            }
        } catch (UsageException exception) {
            System.err.println(exception.getMessage());
            exitCode = 2;
        } catch (Exception exception) {
            System.err.println("admin migration failed: " + exception.getMessage());
            exitCode = 1;
        }
    }

    private void migrate(Flyway flyway, Map<String, String> options) {
        String target = required(options, "target");
        if (!target.equals(required(options, "confirm"))) throw new UsageException("--confirm must equal --target");
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

    private void requireDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT current_database()");
                var result = statement.executeQuery()) {
            result.next();
            if (!EXPECTED_DATABASE.equals(result.getString(1))) {
                throw new UsageException("target database must be " + EXPECTED_DATABASE);
            }
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

    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }
}
