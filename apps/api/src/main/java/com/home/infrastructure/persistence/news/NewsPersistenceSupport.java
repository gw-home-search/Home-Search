package com.home.infrastructure.persistence.news;

import java.time.LocalDate;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;

final class NewsPersistenceSupport {

	private NewsPersistenceSupport() {
	}

	static JdbcClient requiredJdbcClient(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return jdbcClientProvider.getIfAvailable(() -> {
			throw new IllegalStateException("JdbcClient is required for news persistence");
		});
	}

	static LocalDate parseNullableDate(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return LocalDate.parse(value);
	}
}
