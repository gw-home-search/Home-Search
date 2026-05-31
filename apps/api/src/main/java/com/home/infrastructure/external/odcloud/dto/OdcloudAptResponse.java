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
