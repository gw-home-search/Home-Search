package com.home.infrastructure.external.odcloud;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("odcloud.data")
public record OdcloudProperties(
        @NotNull @DefaultValue("https://api.odcloud.kr") URI baseUrl,
        @DefaultValue("") String odServiceKey,
        @DefaultValue("") String aptTitlePath) {

    public OdcloudProperties {
        odServiceKey = odServiceKey == null ? "" : odServiceKey.trim();
        aptTitlePath = aptTitlePath == null ? "" : aptTitlePath.trim();
    }

    public String effectiveAptTitlePath() {
        return aptTitlePath.isEmpty() ? "/api/AptIdInfoSvc/v1/getAptInfo" : aptTitlePath;
    }
}
