package com.home.application.insight.collection;

import com.home.ingestcore.rtms.RtmsDealMonth;
import com.home.ingestcore.rtms.RtmsLawdCode;

public record RtmsCollectionWorkUnitPlan(String lawdCd, String dealYmd) {

    public RtmsCollectionWorkUnitPlan {
        lawdCd = RtmsLawdCode.of(lawdCd).value();
        dealYmd = RtmsDealMonth.of(dealYmd).value();
    }
}
