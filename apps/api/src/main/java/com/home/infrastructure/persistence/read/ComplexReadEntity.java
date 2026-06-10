package com.home.infrastructure.persistence.read;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "complex")
public class ComplexReadEntity {

	@Id
	private Long id;

	@Column(name = "parcel_id")
	private Long parcelId;

	private String name;

	@Column(name = "trade_name")
	private String tradeName;

	@Column(name = "dong_cnt")
	private Integer dongCnt;

	@Column(name = "unit_cnt")
	private Integer unitCnt;

	@Column(name = "plat_area")
	private BigDecimal platArea;

	@Column(name = "arch_area")
	private BigDecimal archArea;

	@Column(name = "tot_area")
	private BigDecimal totArea;

	@Column(name = "bc_rat")
	private BigDecimal bcRat;

	@Column(name = "vl_rat")
	private BigDecimal vlRat;

	@Column(name = "use_date")
	private LocalDate useDate;

	protected ComplexReadEntity() {
	}

	Long id() {
		return id;
	}

	Long parcelId() {
		return parcelId;
	}

	String name() {
		return name;
	}

	String tradeName() {
		return tradeName;
	}

	String displayName() {
		return tradeName == null || tradeName.trim().isEmpty() ? name : tradeName;
	}

	Integer dongCnt() {
		return dongCnt;
	}

	Integer unitCnt() {
		return unitCnt;
	}

	BigDecimal platArea() {
		return platArea;
	}

	BigDecimal archArea() {
		return archArea;
	}

	BigDecimal totArea() {
		return totArea;
	}

	BigDecimal bcRat() {
		return bcRat;
	}

	BigDecimal vlRat() {
		return vlRat;
	}

	LocalDate useDate() {
		return useDate;
	}
}
