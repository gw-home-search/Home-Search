package com.home.infrastructure.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ExternalApiCredentialPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

    @Test
    @DisplayName("기존 external API credential 환경변수 이름을 typed properties로 유지한다")
    void bindsEstablishedEnvironmentVariableNames() {
        contextRunner
                .withPropertyValues(
                        "APT_SERVICE_KEY=apt-key",
                        "ODC_SERVICE_KEY=odc-key",
                        "BLD_SERVICE_KEY=bld-key",
                        "VW_SERVICE_KEY=vw-key")
                .run(context -> {
                    ExternalApiCredentialProperties properties = context.getBean(ExternalApiCredentialProperties.class);

                    assertThat(properties.aptServiceKey("")).isEqualTo("apt-key");
                    assertThat(properties.odcServiceKey("")).isEqualTo("odc-key");
                    assertThat(properties.bldServiceKey("")).isEqualTo("bld-key");
                    assertThat(properties.vwServiceKey("")).isEqualTo("vw-key");
                });
    }

    @Test
    @DisplayName("canonical credential property가 기존 환경변수 fallback보다 우선한다")
    void configuredCredentialTakesPrecedenceOverEnvironmentFallback() {
        ExternalApiCredentialProperties properties =
                new ExternalApiCredentialProperties("legacy-apt", "legacy-odc", "legacy-bld", "legacy-vw");

        assertThat(properties.aptServiceKey("canonical-apt")).isEqualTo("canonical-apt");
        assertThat(properties.odcServiceKey("canonical-odc")).isEqualTo("canonical-odc");
        assertThat(properties.bldServiceKey("canonical-bld")).isEqualTo("canonical-bld");
        assertThat(properties.vwServiceKey("canonical-vw")).isEqualTo("canonical-vw");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ExternalApiCredentialProperties.class)
    static class TestConfiguration {}
}
