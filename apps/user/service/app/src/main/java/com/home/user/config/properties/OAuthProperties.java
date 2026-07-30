package com.home.user.config.properties;

import com.home.domain.user.OAuthProvider;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.oauth")
public record OAuthProperties(
        @NotNull URI successRedirect,
        @NotNull URI failureRedirect,
        @NotEmpty Set<OAuthProvider> enabledProviders) {
    public OAuthProperties {
        enabledProviders = Set.copyOf(enabledProviders);
    }
}
