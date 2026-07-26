package com.home.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

class BatchRuntimeProfileConfigurationTest {

    @Test
    @DisplayName("base batch profile은 기존 NAVER API HUB key 이름을 credential alias로 허용한다")
    void baseProfileAcceptsExistingNaverNewsCredentialAliases() {
        Properties properties = loadMain("application.yml");

        String clientIdPlaceholder = properties.getProperty("home.news.naver.client-id");
        assertThat(clientIdPlaceholder).isEqualTo("${HOME_NEWS_NAVER_CLIENT_ID:${NAVER_NEWS_API_KEY_ID:}}");
        String clientSecretPlaceholder = properties.getProperty("home.news.naver.client-secret");
        assertThat(clientSecretPlaceholder).isEqualTo("${HOME_NEWS_NAVER_CLIENT_SECRET:${NAVER_NEWS_API_KEY:}}");

        MutablePropertySources propertySources = new MutablePropertySources();
        propertySources.addFirst(new MapPropertySource(
                "existing-local-env",
                Map.of(
                        "NAVER_NEWS_API_KEY_ID", "api-hub-client-id",
                        "NAVER_NEWS_API_KEY", "api-hub-client-secret")));

        assertThat(new PropertySourcesPropertyResolver(propertySources)
                        .resolveRequiredPlaceholders(clientIdPlaceholder))
                .isEqualTo("api-hub-client-id");
        assertThat(new PropertySourcesPropertyResolver(propertySources)
                        .resolveRequiredPlaceholders(clientSecretPlaceholder))
                .isEqualTo("api-hub-client-secret");
    }

    @Test
    @DisplayName("staging과 prod batch profile은 one-shot 실행과 provider opt-in을 강제한다")
    void runtimeProfilesKeepJobsAndExternalProvidersExplicitlyDisabled() {
        assertThat(List.of("staging", "prod")).allSatisfy(profile -> {
            Properties properties = load("application-" + profile + ".yml");

            assertThat(properties.getProperty("spring.main.web-application-type"))
                    .isEqualTo("none");
            assertThat(properties.getProperty("spring.batch.job.enabled")).isEqualTo("false");
            assertThat(properties.getProperty("spring.flyway.enabled")).isEqualTo("false");
            assertThat(properties.getProperty("home.events.relay.enabled"))
                    .isEqualTo("${HOME_EVENTS_RELAY_ENABLED:false}");
            assertThat(properties.getProperty("spring.kafka.properties.security.protocol"))
                    .isEqualTo("SASL_SSL");
            assertThat(properties.getProperty("spring.kafka.properties.sasl.mechanism"))
                    .isEqualTo("AWS_MSK_IAM");
            assertThat(properties.getProperty("spring.kafka.producer.acks")).isEqualTo("all");
            assertThat(properties.getProperty("spring.kafka.producer.key-serializer"))
                    .isEqualTo("org.apache.kafka.common.serialization.StringSerializer");
            assertThat(properties.getProperty("spring.kafka.producer.value-serializer"))
                    .isEqualTo("org.apache.kafka.common.serialization.StringSerializer");
            assertThat(properties.getProperty("spring.kafka.producer.properties.enable.idempotence"))
                    .isEqualTo("true");
            assertThat(properties.getProperty("home.news.naver.enabled")).isEqualTo("${HOME_NEWS_NAVER_ENABLED:false}");
            assertThat(properties.getProperty("home.insight.trade.enabled"))
                    .isEqualTo("${HOME_INSIGHT_TRADE_ENABLED:false}");
            assertThat(properties.getProperty("home.ingest.rtms.daily.enabled"))
                    .isEqualTo("${HOME_INGEST_RTMS_DAILY_ENABLED:false}");
        });
    }

    private Properties load(String fileName) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(fileName));
        return factory.getObject();
    }

    private Properties loadMain(String fileName) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource("src/main/resources/" + fileName));
        return factory.getObject();
    }
}
