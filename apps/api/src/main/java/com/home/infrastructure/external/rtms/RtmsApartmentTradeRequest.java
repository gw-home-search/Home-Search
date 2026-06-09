package com.home.infrastructure.external.rtms;

import com.home.domain.ingest.rtms.RtmsDealMonth;
import com.home.domain.ingest.rtms.RtmsLawdCode;

public record RtmsApartmentTradeRequest(
	String lawdCd,
	String dealYmd,
	Integer pageNo
) {

	public RtmsApartmentTradeRequest {
		lawdCd = RtmsLawdCode.of(lawdCd).value();
		dealYmd = RtmsDealMonth.of(dealYmd).value();
		pageNo = pageNo == null ? 1 : pageNo;
		if (pageNo < 1) {
			throw new IllegalArgumentException("pageNo must be greater than zero");
		}
	}
}
