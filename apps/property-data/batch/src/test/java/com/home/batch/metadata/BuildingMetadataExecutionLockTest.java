package com.home.batch.metadata;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingMetadataExecutionLockTest {
	@Test
	@DisplayName("session advisory lock은 acquire와 unlock을 같은 JDBC connection에서 수행한다")
	void holdsSameConnectionUntilUnlock() throws Exception {
		DataSource dataSource = mock(DataSource.class); Connection connection = mock(Connection.class);
		PreparedStatement acquire = mock(PreparedStatement.class); PreparedStatement release = mock(PreparedStatement.class);
		ResultSet acquired = result(true); ResultSet released = result(true);
		given(dataSource.getConnection()).willReturn(connection);
		given(connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")).willReturn(acquire);
		given(connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")).willReturn(release);
		given(acquire.executeQuery()).willReturn(acquired); given(release.executeQuery()).willReturn(released);
		BuildingMetadataExecutionLock executionLock = new BuildingMetadataExecutionLock(dataSource);

		try (BuildingMetadataExecutionLock.Lock ignored = executionLock.acquire()) { }

		verify(acquire).setString(1,"complex-building-metadata-job");
		verify(release).setString(1,"complex-building-metadata-job");
		verify(connection).close();
	}

	@Test
	@DisplayName("advisory lock 획득 실패도 dedicated connection을 즉시 닫는다")
	void closesConnectionWhenLockIsBusy() throws Exception {
		DataSource dataSource = mock(DataSource.class); Connection connection = mock(Connection.class);
		PreparedStatement acquire = mock(PreparedStatement.class);
		given(dataSource.getConnection()).willReturn(connection);
		given(connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")).willReturn(acquire);
		ResultSet busy = result(false);
		given(acquire.executeQuery()).willReturn(busy);

		assertThatThrownBy(() -> new BuildingMetadataExecutionLock(dataSource).acquire())
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("another building metadata job");
		verify(connection).close();
	}

	private ResultSet result(boolean value) throws Exception {
		ResultSet result = mock(ResultSet.class); given(result.next()).willReturn(true); given(result.getBoolean(1)).willReturn(value);
		return result;
	}
}
