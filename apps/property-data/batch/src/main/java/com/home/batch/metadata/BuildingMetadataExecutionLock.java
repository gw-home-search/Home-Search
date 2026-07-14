package com.home.batch.metadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

class BuildingMetadataExecutionLock {
    private static final String LOCK_NAME = "complex-building-metadata-job";
    private static final String ACQUIRE_SQL = "SELECT pg_try_advisory_lock(hashtext(?))";
    private static final String RELEASE_SQL = "SELECT pg_advisory_unlock(hashtext(?))";
    private final DataSource dataSource;

    BuildingMetadataExecutionLock(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    Lock acquire() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            if (!execute(connection, ACQUIRE_SQL)) {
                connection.close();
                throw new IllegalStateException("another building metadata job is running");
            }
            Connection heldConnection = connection;
            return () -> release(heldConnection);
        } catch (SQLException exception) {
            closeQuietly(connection);
            throw new IllegalStateException("building metadata advisory lock failed", exception);
        }
    }

    private boolean execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void release(Connection connection) {
        try {
            if (!execute(connection, RELEASE_SQL))
                throw new IllegalStateException("building metadata advisory unlock failed");
        } catch (SQLException exception) {
            throw new IllegalStateException("building metadata advisory unlock failed", exception);
        } finally {
            closeQuietly(connection);
        }
    }

    private void closeQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    @FunctionalInterface
    interface Lock extends AutoCloseable {
        @Override
        void close();
    }
}
