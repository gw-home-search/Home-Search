package com.home.infrastructure.external.news;

import com.home.application.news.collection.MarketNewsCollectionRepository;
import com.home.application.news.collection.MarketNewsCollectionService;
import com.home.application.news.collection.MarketNewsPublicationCache;
import com.home.application.news.collection.NewsItemNormalizationGateway;
import com.home.application.news.collection.NewsProviderGateway;
import com.home.application.news.quality.MarketNewsQualityRepository;
import com.home.application.news.quality.MarketNewsQualitySamplingRepository;
import com.home.application.news.quality.MarketNewsQualitySamplingService;
import com.home.application.news.quality.MarketNewsQualityService;
import com.home.application.news.retention.MarketNewsRetentionRepository;
import com.home.application.news.retention.MarketNewsRetentionService;
import com.home.application.news.selection.MajorNewsComplexSelectionRepository;
import com.home.application.news.selection.MajorNewsComplexSelectionService;
import com.home.infrastructure.cache.news.NoopMarketNewsPublicationCache;
import com.home.infrastructure.cache.news.RedisMarketNewsPublicationCache;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnNotWebApplication
@EnableConfigurationProperties(NaverNewsProperties.class)
public class NaverNewsConfiguration {

    @Bean
    NewsItemNormalizationGateway naverNewsItemNormalizer() {
        return new NaverNewsItemNormalizer();
    }

    @Bean
    @ConditionalOnProperty(prefix = "home.news.naver", name = "enabled", havingValue = "true")
    NewsProviderGateway naverNewsProviderGateway(NaverNewsProperties properties, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.baseUrl().toString())
                .defaultHeader(properties.providerMode().clientIdHeader(), properties.clientId())
                .defaultHeader(properties.providerMode().clientSecretHeader(), properties.clientSecret())
                .build();
        return new NaverNewsApiClient(restClient, objectMapper, properties.path());
    }

    @Bean
    @ConditionalOnProperty(prefix = "home.news.naver", name = "enabled", havingValue = "true")
    MarketNewsCollectionService marketNewsCollectionService(
            MarketNewsCollectionRepository repository,
            NewsProviderGateway provider,
            NewsItemNormalizationGateway normalizer,
            MarketNewsPublicationCache publicationCache) {
        return new MarketNewsCollectionService(repository, provider, normalizer, publicationCache);
    }

    @Bean
    @ConditionalOnProperty(prefix = "home.news.naver", name = "cache-enabled", havingValue = "true")
    MarketNewsPublicationCache redisMarketNewsPublicationCache(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, NaverNewsProperties properties) {
        return new RedisMarketNewsPublicationCache(redisTemplate, objectMapper, properties.cacheTtl());
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "home.news.naver",
            name = "cache-enabled",
            havingValue = "false",
            matchIfMissing = true)
    MarketNewsPublicationCache noopMarketNewsPublicationCache() {
        return new NoopMarketNewsPublicationCache();
    }

    @Bean
    MajorNewsComplexSelectionService majorNewsComplexSelectionService(MajorNewsComplexSelectionRepository repository) {
        return new MajorNewsComplexSelectionService(repository);
    }

    @Bean
    MarketNewsRetentionService marketNewsRetentionService(MarketNewsRetentionRepository repository) {
        return new MarketNewsRetentionService(repository);
    }

    @Bean
    MarketNewsQualityService marketNewsQualityService(
            MarketNewsQualityRepository repository, MarketNewsPublicationCache publicationCache) {
        return new MarketNewsQualityService(repository, publicationCache);
    }

    @Bean
    MarketNewsQualitySamplingService marketNewsQualitySamplingService(MarketNewsQualitySamplingRepository repository) {
        return new MarketNewsQualitySamplingService(repository);
    }
}
