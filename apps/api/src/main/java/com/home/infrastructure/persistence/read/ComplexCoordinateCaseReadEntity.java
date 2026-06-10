package com.home.infrastructure.persistence.read;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

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

	protected ComplexCoordinateCaseReadEntity() {
	}
}
