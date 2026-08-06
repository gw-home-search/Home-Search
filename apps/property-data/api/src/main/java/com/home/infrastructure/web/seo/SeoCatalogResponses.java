package com.home.infrastructure.web.seo;

final class SeoCatalogResponses {
    private SeoCatalogResponses() {}

    record Complex(Long complexId) {}

    record Region(Long regionId) {}
}
