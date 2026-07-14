package com.home.infrastructure.external.complex;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("apis.data")
public record BuildingApiProperties(
        @NotNull @DefaultValue("https://apis.data.go.kr") URI baseUrl,
        @DefaultValue("") String bldServiceKey,
        @DefaultValue("") String buildingTitlePath,
        @DefaultValue("") String buildingRecapTitlePath,

        @DefaultValue("/1613000/BldRgstHubService/getBrRecapTitleInfo")
        String bldTitlePath,

        @DefaultValue("/1613000/BldRgstHubService/getBrTitleInfo")
        String recapTitlePath) {}
