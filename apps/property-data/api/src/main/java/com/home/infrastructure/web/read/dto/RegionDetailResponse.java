package com.home.infrastructure.web.read.dto;

import com.home.application.read.RegionDetailResult;
import java.util.List;

public record RegionDetailResponse(
        Long id, String name, Double latitude, Double longitude, List<RegionSummaryResponse> children) {

    public static RegionDetailResponse from(RegionDetailResult result) {
        return new RegionDetailResponse(
                result.id(),
                result.name(),
                result.latitude(),
                result.longitude(),
                result.children().stream().map(RegionSummaryResponse::from).toList());
    }
}
