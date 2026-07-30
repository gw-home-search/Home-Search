package com.home.user.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.domain.user.OAuthProvider;
import com.home.user.config.properties.AuthProperties;
import com.home.user.config.properties.JwtProperties;
import com.home.user.config.properties.OAuthProperties;
import jakarta.validation.Validation;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UserConfigurationPropertiesTest {
    @Test
    void rejectsNonPositiveAuthAndJwtDurations() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();

            assertThat(validator.validate(new AuthProperties(URI.create("https://home.example"), Duration.ZERO)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("refreshTtlPositive");
            assertThat(validator.validate(new JwtProperties(
                            "active",
                            Path.of("private.pem"),
                            Path.of("public.pem"),
                            "",
                            "",
                            "user-service",
                            "home-search-user-api",
                            Duration.ZERO)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("lifetimePositive");
            assertThat(validator.validate(new OAuthProperties(
                            URI.create("https://home.example/auth/success"),
                            URI.create("https://home.example/auth/failure"),
                            Set.of())))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("enabledProviders");
            assertThat(new OAuthProperties(
                                    URI.create("https://home.example/auth/success"),
                                    URI.create("https://home.example/auth/failure"),
                                    Set.of(OAuthProvider.KAKAO))
                            .enabledProviders())
                    .containsExactly(OAuthProvider.KAKAO);
        }
    }
}
