package com.home.infrastructure.persistence.read;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

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
@Table(name = "trade")
public class TradeReadEntity {

	@Id
	private Long id;

	@Column(name = "complex_id")
	private Long complexId;

	@Column(name = "deal_date")
	private LocalDate dealDate;

	@Column(name = "deal_amount")
	private Long dealAmount;

	private Integer floor;

	@Column(name = "excl_area")
	private BigDecimal exclArea;

	@Column(name = "apt_dong")
	private String aptDong;

	@Column(name = "deleted_at")
	private OffsetDateTime deletedAt;
}
