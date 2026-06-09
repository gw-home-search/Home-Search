package com.home.application.ingest.backfill;

import com.home.domain.ingest.rtms.RtmsDealMonth;
import com.home.domain.ingest.rtms.RtmsLawdCode;

public record RtmsBackfillChunkRequest(String lawdCd, String dealYmd) {

	public RtmsBackfillChunkRequest {
		lawdCd = RtmsLawdCode.of(lawdCd).value();
		dealYmd = RtmsDealMonth.of(dealYmd).value();
	}
}
