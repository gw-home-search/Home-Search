package com.home.application.read;

public record RegionSummaryResult(Long id, String name, String code) {

    public RegionSummaryResult(Long id, String name) {
        this(id, name, null);
    }
}
