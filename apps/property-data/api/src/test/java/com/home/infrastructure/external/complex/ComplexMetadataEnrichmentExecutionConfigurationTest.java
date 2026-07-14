package com.home.infrastructure.external.complex;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ComplexMetadataEnrichmentExecutionConfigurationTest {
    @Test
    @DisplayName("API metadata runner와 scheduler는 기본 설정에서 등록되지 않는다")
    void runnerAndSchedulerAreDisabledByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(ComplexMetadataEnrichmentExecutionConfiguration.class)
                .run(context -> assertThat(context)
                        .doesNotHaveBean("complexMetadataEnrichmentRunner")
                        .doesNotHaveBean("complexMetadataEnrichmentScheduler"));
    }
}
