package com.home.application.read;

import java.util.List;

public record RegionDetailResult(
        Long id, String name, String code, Double latitude, Double longitude, List<RegionSummaryResult> children) {

    public RegionDetailResult(
            Long id, String name, Double latitude, Double longitude, List<RegionSummaryResult> children) {
        this(id, name, null, latitude, longitude, children);
    }
}
