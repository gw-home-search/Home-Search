package com.home.infrastructure.persistence.read;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import org.hibernate.annotations.Immutable;

@Getter(AccessLevel.PACKAGE)
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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
