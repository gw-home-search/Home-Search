package com.home.sourcedata.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Proxy;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class SourceDataMigrationRunnerTest {
    @Test
    void parsesExplicitOperationAndConfirmation() {
        assertThat(SourceDataMigrationRunner.parse(new String[] {"--operation=migrate", "--target=3", "--confirm=3"}))
                .containsEntry("operation", "migrate")
                .containsEntry("target", "3");
    }

    @Test
    void ownsTheSupportedOperationsAsAnInternalEnum() {
        assertThatCode(() -> Class.forName("com.home.sourcedata.migration.SourceDataMigrationRunner$Operation"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingUnsupportedDuplicateAndUnconfirmedOperationsWithUsageExitCode() {
        for (String[] arguments : new String[][] {{}, {"--operation=unknown"}, {"--operation=migrate", "--target=4"}}) {
            SourceDataMigrationRunner runner = new SourceDataMigrationRunner(expectedDatabase());
            runner.run(new DefaultApplicationArguments(arguments));
            assertThat(runner.getExitCode()).isEqualTo(2);
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        SourceDataMigrationRunner.parse(new String[] {"--operation=info", "--operation=validate"}))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("duplicate option");
    }

    private DataSource expectedDatabase() {
        var resultSet = proxy(java.sql.ResultSet.class, (method, arguments) -> switch (method.getName()) {
            case "next" -> true;
            case "getString" -> SourceDataMigrationRunner.EXPECTED_DATABASE;
            default -> defaultValue(method.getReturnType());
        });
        var statement = proxy(
                java.sql.PreparedStatement.class,
                (method, arguments) ->
                        "executeQuery".equals(method.getName()) ? resultSet : defaultValue(method.getReturnType()));
        var connection = proxy(
                java.sql.Connection.class,
                (method, arguments) ->
                        "prepareStatement".equals(method.getName()) ? statement : defaultValue(method.getReturnType()));
        return proxy(
                DataSource.class,
                (method, arguments) ->
                        "getConnection".equals(method.getName()) ? connection : defaultValue(method.getReturnType()));
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (target, method, arguments) -> invocation.invoke(method, arguments));
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] arguments) throws Throwable;
    }
}
