package com.home.infrastructure.persistence.coordinate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.home.application.coordinate.readiness.ComplexCoordinateReadinessService;
import com.home.domain.coordinate.CoordinateIdentityBlockingPolicy;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ComplexCoordinatePersistenceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(JdbcTemplateAutoConfiguration.class, JdbcClientAutoConfiguration.class))
            .withUserConfiguration(ComplexCoordinatePersistenceConfiguration.class)
            .withPropertyValues("home.coordinate.readiness.enabled=true")
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(ComplexCoordinateReadinessService.class, () -> mock(ComplexCoordinateReadinessService.class));

    @Test
    @DisplayName("coordinate readiness runner는 JdbcClient auto-config 환경에서 시작된다")
    void coordinateReadinessRunnerStartsWithJdbcClientAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ComplexCoordinateReadinessService.class);
            assertThat(context).hasSingleBean(ComplexCoordinateReadinessRunner.class);
        });
    }

    @Test
    @DisplayName("ODC identity strict block은 기본 설정에서 unavailable과 failed를 모두 차단한다")
    void odcloudIdentityStrictBlockIsDefaultOn() {
        contextRunner.run(context -> {
            assertThat(context.getBean(CoordinateIdentityBlockingPolicy.class))
                    .isEqualTo(CoordinateIdentityBlockingPolicy.strict());
        });
    }

    @Test
    @DisplayName("ODC identity strict block은 명시 property로 unavailable과 failed degrade를 허용할 수 있다")
    void odcloudIdentityStrictBlockCanBeExplicitlyDisabled() {
        contextRunner
                .withPropertyValues(
                        "complex.coordinate.identity.block-on-unavailable=false",
                        "complex.coordinate.identity.block-on-failed=false")
                .run(context -> {
                    assertThat(context.getBean(CoordinateIdentityBlockingPolicy.class))
                            .isEqualTo(CoordinateIdentityBlockingPolicy.degradeUnavailableAndFailed());
                });
    }
}
