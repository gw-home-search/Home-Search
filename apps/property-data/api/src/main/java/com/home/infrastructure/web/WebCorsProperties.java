package com.home.infrastructure.web;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("frontend")
public record WebCorsProperties(
        @NotNull @DefaultValue("http://localhost:5173") URI url) {}
