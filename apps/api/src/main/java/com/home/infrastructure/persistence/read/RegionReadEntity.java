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
@Table(name = "region")
public class RegionReadEntity {

	@Id
	private Long id;

	@Column(name = "parent_id")
	private Long parentId;

	private String name;

	@Column(name = "center_lat")
	private BigDecimal centerLat;

	@Column(name = "center_lng")
	private BigDecimal centerLng;

	Double centerLat() {
		return doubleOrNull(centerLat);
	}

	Double centerLng() {
		return doubleOrNull(centerLng);
	}

	private static Double doubleOrNull(BigDecimal value) {
		return value == null ? null : value.doubleValue();
	}
}
