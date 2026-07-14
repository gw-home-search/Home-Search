package com.home.user.config.properties;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.oauth")
public record OAuthProperties(
        @NotNull URI successRedirect, @NotNull URI failureRedirect) {}
