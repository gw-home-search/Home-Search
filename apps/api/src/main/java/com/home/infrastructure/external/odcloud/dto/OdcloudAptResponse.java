package com.home.infrastructure.external.odcloud.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OdcloudAptResponse {

	@JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
	private List<Item> data;

	public List<Item> getData() {
		return data;
	}

	public void setData(List<Item> data) {
		this.data = data;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Item {
		@JsonProperty("ADRES")
		private String address;
		@JsonProperty("COMPLEX_PK")
		private String complexPk;
		@JsonProperty("COMPLEX_NM1")
		private String complexNm1;
		@JsonProperty("COMPLEX_NM2")
		private String complexNm2;
		@JsonProperty("COMPLEX_NM3")
		private String complexNm3;
		@JsonProperty("DONG_CNT")
		private Integer dongCnt;
		@JsonProperty("PNU")
		private String pnu;
		@JsonProperty("UNIT_CNT")
		private Integer unitCnt;
		@JsonProperty("USEAPR_DT")
		private String useaprDt;

		public String getAddress() {
			return address;
		}

		public void setAddress(String address) {
			this.address = address;
		}

		public String getComplexPk() {
			return complexPk;
		}

		public void setComplexPk(String complexPk) {
			this.complexPk = complexPk;
		}

		public String getComplexNm1() {
			return complexNm1;
		}

		public void setComplexNm1(String complexNm1) {
			this.complexNm1 = complexNm1;
		}

		public String getComplexNm2() {
			return complexNm2;
		}

		public void setComplexNm2(String complexNm2) {
			this.complexNm2 = complexNm2;
		}

		public String getComplexNm3() {
			return complexNm3;
		}

		public void setComplexNm3(String complexNm3) {
			this.complexNm3 = complexNm3;
		}

		public Integer getDongCnt() {
			return dongCnt;
		}

		public void setDongCnt(Integer dongCnt) {
			this.dongCnt = dongCnt;
		}

		public String getPnu() {
			return pnu;
		}

		public void setPnu(String pnu) {
			this.pnu = pnu;
		}

		public Integer getUnitCnt() {
			return unitCnt;
		}

		public void setUnitCnt(Integer unitCnt) {
			this.unitCnt = unitCnt;
		}

		public String getUseaprDt() {
			return useaprDt;
		}

		public void setUseaprDt(String useaprDt) {
			this.useaprDt = useaprDt;
		}
	}
}
