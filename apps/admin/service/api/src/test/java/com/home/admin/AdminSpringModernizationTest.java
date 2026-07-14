package com.home.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.home.admin.config.AdminSessionProperties;
import com.home.admin.config.InternalAdminClientProperties;
import com.home.admin.config.InternalAdminJwtProperties;
import jakarta.validation.Validation;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdminSpringModernizationTest {
    @Test
    void usesTypedAdminRuntimePropertiesAndACommonProblemFactory() {
        assertThatCode(() -> Class.forName("com.home.admin.config.AdminSessionProperties"))
                .doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("com.home.admin.config.InternalAdminClientProperties"))
                .doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("com.home.admin.config.InternalAdminJwtProperties"))
                .doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("com.home.admin.AdminProblemFactory"))
                .doesNotThrowAnyException();
    }

    @Test
    void validatesEnabledInternalConfigurationButAllowsDisabledSecretsToStayBlank() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();

            assertThat(validator.validate(new AdminSessionProperties(Duration.ZERO)))
                    .isNotEmpty();
            assertThat(validator.validate(new InternalAdminClientProperties(
                            true,
                            URI.create("http://user:password@property-data"),
                            Duration.ofSeconds(2),
                            Duration.ofSeconds(10))))
                    .isNotEmpty();
            assertThat(validator.validate(new InternalAdminJwtProperties(
                            true, "admin-service", "property-data-admin", Duration.ofSeconds(60), "", "")))
                    .isNotEmpty();
            assertThat(validator.validate(new InternalAdminJwtProperties(
                            false, "admin-service", "property-data-admin", Duration.ofSeconds(60), "", "")))
                    .isEmpty();
        }
    }
}
