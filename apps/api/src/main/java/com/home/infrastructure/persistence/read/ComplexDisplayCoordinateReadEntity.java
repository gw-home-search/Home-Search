package com.home.infrastructure.persistence.read;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "complex_display_coordinate")
public class ComplexDisplayCoordinateReadEntity {

	@Id
	@Column(name = "complex_id")
	private Long complexId;

	private BigDecimal latitude;

	private BigDecimal longitude;

	protected ComplexDisplayCoordinateReadEntity() {
	}

	Long complexId() {
		return complexId;
	}

	Double latitude() {
		return doubleOrNull(latitude);
	}

	Double longitude() {
		return doubleOrNull(longitude);
	}

	private static Double doubleOrNull(BigDecimal value) {
		return value == null ? null : value.doubleValue();
	}
}
