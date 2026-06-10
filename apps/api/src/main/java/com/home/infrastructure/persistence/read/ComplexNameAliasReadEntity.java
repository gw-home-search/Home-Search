package com.home.infrastructure.persistence.read;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "complex_name_alias")
public class ComplexNameAliasReadEntity {

	@Id
	private Long id;

	@Column(name = "complex_id")
	private Long complexId;

	@Column(name = "alias_name")
	private String aliasName;

	@Column(name = "normalized_name")
	private String normalizedName;

	protected ComplexNameAliasReadEntity() {
	}
}
