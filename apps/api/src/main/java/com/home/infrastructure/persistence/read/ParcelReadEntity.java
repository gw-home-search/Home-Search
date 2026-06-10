package com.home.infrastructure.persistence.read;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "parcel")
public class ParcelReadEntity {

	@Id
	private Long id;

	private String address;

	private BigDecimal latitude;

	private BigDecimal longitude;

	@Column(name = "region_id")
	private Long regionId;

	protected ParcelReadEntity() {
	}

	Long id() {
		return id;
	}

	String address() {
		return address;
	}

	Double latitude() {
		return doubleOrNull(latitude);
	}

	Double longitude() {
		return doubleOrNull(longitude);
	}

	Long regionId() {
		return regionId;
	}

	private static Double doubleOrNull(BigDecimal value) {
		return value == null ? null : value.doubleValue();
	}
}
