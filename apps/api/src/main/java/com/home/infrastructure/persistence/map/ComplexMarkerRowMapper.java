package com.home.infrastructure.persistence.map;

import java.sql.ResultSet;
import java.sql.SQLException;

import com.home.application.map.ComplexMarkerResult;

final class ComplexMarkerRowMapper {

	ComplexMarkerResult map(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ComplexMarkerResult(
			resultSet.getLong("parcel_id"),
			longOrNull(resultSet, "complex_id"),
			resultSet.getString("complex_name"),
			resultSet.getDouble("lat"),
			resultSet.getDouble("lng"),
			longOrNull(resultSet, "latest_deal_amount"),
			resultSet.getLong("unit_cnt_sum")
		);
	}

	private Long longOrNull(ResultSet resultSet, String columnName) throws SQLException {
		long value = resultSet.getLong(columnName);
		return resultSet.wasNull() ? null : value;
	}
}
