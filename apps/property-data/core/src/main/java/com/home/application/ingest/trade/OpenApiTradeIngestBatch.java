package com.home.application.ingest.trade;

import com.home.domain.ingest.source.IngestSource;
import com.home.ingestcore.rtms.OpenApiTradeItem;
import com.home.ingestcore.rtms.RtmsDealMonth;
import com.home.ingestcore.rtms.RtmsLawdCode;
import java.util.List;

public record OpenApiTradeIngestBatch(
        String source, String lawdCd, String dealYmd, Integer pageNo, List<OpenApiTradeItem> items) {

    public OpenApiTradeIngestBatch {
        source = IngestSource.ofOrDefault(source, "RTMS").value();
        lawdCd = RtmsLawdCode.of(lawdCd).value();
        dealYmd = RtmsDealMonth.of(dealYmd).value();
        pageNo = pageNo == null ? 1 : pageNo;
        items = items == null ? List.of() : List.copyOf(items);
    }
}
