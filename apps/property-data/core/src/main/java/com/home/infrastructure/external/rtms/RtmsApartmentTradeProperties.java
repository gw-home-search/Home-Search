package com.home.infrastructure.external.rtms;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("apis.data")
public record RtmsApartmentTradeProperties(
        @DefaultValue("https://apis.data.go.kr") String baseUrl,

        @DefaultValue("/1613000/RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev")
        String aptTitlePath,

        @DefaultValue("") String aptServiceKey,
        @Positive @DefaultValue("1000") int aptNumOfRows,
        @Positive @DefaultValue("5000") int connectTimeoutMillis,
        @Positive @DefaultValue("5000") int readTimeoutMillis,
        @Positive @DefaultValue("200") long minRequestIntervalMillis) {

    public RtmsApartmentTradeProperties {
        baseUrl = hasText(baseUrl) ? baseUrl.trim() : "https://apis.data.go.kr";
        aptTitlePath = hasText(aptTitlePath)
                ? aptTitlePath.trim()
                : "/1613000/RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev";
        aptNumOfRows = aptNumOfRows > 0 ? aptNumOfRows : 1000;
        connectTimeoutMillis = connectTimeoutMillis > 0 ? connectTimeoutMillis : 5_000;
        readTimeoutMillis = readTimeoutMillis > 0 ? readTimeoutMillis : 5_000;
        minRequestIntervalMillis = minRequestIntervalMillis > 0 ? minRequestIntervalMillis : 200;
    }

    public String requiredServiceKey() {
        if (!hasText(aptServiceKey)) {
            throw new IllegalStateException("APT_SERVICE_KEY is required for live RTMS calls");
        }
        return aptServiceKey.trim();
    }

    public String path() {
        return aptTitlePath;
    }

    public int numOfRows() {
        return aptNumOfRows;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
