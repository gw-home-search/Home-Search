package com.home.infrastructure.persistence.read;

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
@Table(name = "complex_coordinate_case")
public class ComplexCoordinateCaseReadEntity {

	@Id
	private Long id;

	@Column(name = "parcel_id")
	private Long parcelId;

	@Column(name = "relation_type")
	private String relationType;

	@Column(name = "relation_confidence")
	private String relationConfidence;
}
