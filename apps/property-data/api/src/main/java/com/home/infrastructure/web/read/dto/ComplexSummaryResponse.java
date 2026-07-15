package com.home.infrastructure.web.read.dto;

import com.home.application.read.ComplexSummaryResult;
import java.time.LocalDate;

public record ComplexSummaryResponse(
        Long complexId,
        String complexName,
        Long parcelId,
        Double latitude,
        Double longitude,
        String address,
        Integer dongCnt,
        Integer unitCnt,
        LocalDate useDate) {

    public static ComplexSummaryResponse from(ComplexSummaryResult result) {
        return new ComplexSummaryResponse(
                result.complexId(),
                result.complexName(),
                result.parcelId(),
                result.latitude(),
                result.longitude(),
                result.address(),
                result.dongCnt(),
                result.unitCnt(),
                result.useDate());
    }
}
