package com.home.infrastructure.external.vworld;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("vworld.data")
record VworldParcelCoordinateProperties(
        @DefaultValue("https://api.vworld.kr") String baseUrl,
        @DefaultValue("/ned/wfs/getBldgisSpceWFS") String vmWfsPath,
        @DefaultValue("") String vwServiceKey,

        @DefaultValue("http://localhost:8080/only-local-test")
        String vmDomain,

        @Positive @DefaultValue("100") int numOfRows,
        @Positive @DefaultValue("5000") int connectTimeoutMillis,
        @Positive @DefaultValue("5000") int readTimeoutMillis) {

    VworldParcelCoordinateProperties {
        baseUrl = hasText(baseUrl) ? baseUrl.trim() : "https://api.vworld.kr";
        vmWfsPath = hasText(vmWfsPath) ? vmWfsPath.trim() : "/ned/wfs/getBldgisSpceWFS";
        vwServiceKey = hasText(vwServiceKey) ? vwServiceKey.trim() : "";
        vmDomain = hasText(vmDomain) ? vmDomain.trim() : "http://localhost:8080/only-local-test";
        numOfRows = numOfRows > 0 ? numOfRows : 100;
        connectTimeoutMillis = connectTimeoutMillis > 0 ? connectTimeoutMillis : 5_000;
        readTimeoutMillis = readTimeoutMillis > 0 ? readTimeoutMillis : 5_000;
    }

    boolean hasServiceKey() {
        return hasText(vwServiceKey);
    }

    String wfsPath() {
        return vmWfsPath;
    }

    String serviceKey() {
        return vwServiceKey;
    }

    String domain() {
        return vmDomain;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
