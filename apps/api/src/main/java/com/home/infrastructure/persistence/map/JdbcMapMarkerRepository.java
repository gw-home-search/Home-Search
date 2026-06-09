package com.home.infrastructure.persistence.map;

import java.util.List;
import java.util.Objects;

import com.home.application.map.ComplexMarkerQuery;
import com.home.application.map.ComplexMarkerRepository;
import com.home.application.map.ComplexMarkerResult;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcMapMarkerRepository implements ComplexMarkerRepository {

	private final JdbcClient jdbcClient;
	private final ComplexMarkerRowMapper rowMapper = new ComplexMarkerRowMapper();

	public JdbcMapMarkerRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public List<ComplexMarkerResult> findComplexMarkers(ComplexMarkerQuery query) {
		if (hasMarkerShapeFilter(query)) {
			return findComplexMarkersWithMarkerShapeFilter(query);
		}
		return findComplexMarkersWithTradeFirst(query);
	}

	private List<ComplexMarkerResult> findComplexMarkersWithMarkerShapeFilter(ComplexMarkerQuery query) {
		ComplexMarkerJdbcParameters parameters = ComplexMarkerJdbcParameters.from(query);
		return parameters.bindMarkerShapeFilter(jdbcClient.sql(ComplexMarkerSql.markerShapeFilter()))
			.query(rowMapper::map)
			.list();
	}

	private List<ComplexMarkerResult> findComplexMarkersWithTradeFirst(ComplexMarkerQuery query) {
		ComplexMarkerJdbcParameters parameters = ComplexMarkerJdbcParameters.from(query);
		return parameters.bindTradeFirst(jdbcClient.sql(ComplexMarkerSql.tradeFirst()))
			.query(rowMapper::map)
			.list();
	}

	private boolean hasMarkerShapeFilter(ComplexMarkerQuery query) {
		return query.unitMin() != null
			|| query.unitMax() != null
			|| query.ageMin() != null
			|| query.ageMax() != null;
	}
}
