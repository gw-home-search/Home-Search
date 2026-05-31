package com.home.infrastructure.external.apis.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ApisBldRecapResponse {

	private Response response;

	public Response getResponse() {
		return response;
	}

	public void setResponse(Response response) {
		this.response = response;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Response {
		private Body body;

		public Body getBody() {
			return body;
		}

		public void setBody(Body body) {
			this.body = body;
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Body {
		private Items items;

		public Items getItems() {
			return items;
		}

		public void setItems(Items items) {
			this.items = items;
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Items {
		private List<Item> item;

		public List<Item> getItem() {
			return item;
		}

		public void setItem(List<Item> item) {
			this.item = item;
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Item {
		private String mainPurpsCd;
		private Integer hhldCnt;
		private Double platArea;
		private Double archArea;
		private Double bcRat;
		private Double totArea;
		private Double vlRat;

		public String getMainPurpsCd() {
			return mainPurpsCd;
		}

		public void setMainPurpsCd(String mainPurpsCd) {
			this.mainPurpsCd = mainPurpsCd;
		}

		public Integer getHhldCnt() {
			return hhldCnt;
		}

		public void setHhldCnt(Integer hhldCnt) {
			this.hhldCnt = hhldCnt;
		}

		public Double getPlatArea() {
			return platArea;
		}

		public void setPlatArea(Double platArea) {
			this.platArea = platArea;
		}

		public Double getArchArea() {
			return archArea;
		}

		public void setArchArea(Double archArea) {
			this.archArea = archArea;
		}

		public Double getBcRat() {
			return bcRat;
		}

		public void setBcRat(Double bcRat) {
			this.bcRat = bcRat;
		}

		public Double getTotArea() {
			return totArea;
		}

		public void setTotArea(Double totArea) {
			this.totArea = totArea;
		}

		public Double getVlRat() {
			return vlRat;
		}

		public void setVlRat(Double vlRat) {
			this.vlRat = vlRat;
		}
	}
}
