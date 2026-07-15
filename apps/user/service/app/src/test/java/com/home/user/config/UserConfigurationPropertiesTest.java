package com.home.user.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.user.config.properties.AuthProperties;
import com.home.user.config.properties.JwtProperties;
import jakarta.validation.Validation;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
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
        }
    }
}
