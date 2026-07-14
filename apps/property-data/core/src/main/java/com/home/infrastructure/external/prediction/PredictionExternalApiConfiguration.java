package com.home.infrastructure.external.prediction;

import com.home.application.prediction.PredictionClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PredictionRuntimeProperties.class)
class PredictionExternalApiConfiguration {

    @Bean
    @ConditionalOnMissingBean(PredictionClient.class)
    PredictionClient predictionClient(PredictionRuntimeProperties properties) {
        PredictionRuntimeProperties.Client client = properties.client();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(client.connectTimeoutMillis());
        requestFactory.setReadTimeout(client.readTimeoutMillis());
        return new PythonPredictionClient(RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(client.baseUrl().toString())
                .build());
    }
}
