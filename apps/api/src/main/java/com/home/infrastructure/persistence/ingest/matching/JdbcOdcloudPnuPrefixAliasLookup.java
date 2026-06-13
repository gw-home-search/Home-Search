package com.home.infrastructure.persistence.ingest.matching;

import java.util.Objects;
import java.util.Optional;

import com.home.application.ingest.metadata.OdcloudPnuPrefixAlias;
import com.home.application.ingest.metadata.OdcloudPnuPrefixAliasLookup;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcOdcloudPnuPrefixAliasLookup implements OdcloudPnuPrefixAliasLookup {

	private final JdbcClient jdbcClient;

	public JdbcOdcloudPnuPrefixAliasLookup(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public Optional<OdcloudPnuPrefixAlias> findApprovedByCanonicalPnu(String canonicalPnu) {
		if (canonicalPnu == null || !canonicalPnu.matches("\\d{19}")) {
			return Optional.empty();
		}
		return jdbcClient.sql("""
			SELECT id, canonical_prefix, source_prefix
			FROM odcloud_pnu_prefix_alias
			WHERE canonical_prefix = substring(:canonicalPnu, 1, 8)
			  AND status = 'APPROVED'
			""")
			.param("canonicalPnu", canonicalPnu)
			.query((resultSet, rowNumber) -> new OdcloudPnuPrefixAlias(
				resultSet.getLong("id"),
				resultSet.getString("canonical_prefix").trim(),
				resultSet.getString("source_prefix").trim()
			))
			.optional();
	}
}
