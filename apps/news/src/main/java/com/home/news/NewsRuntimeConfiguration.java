package com.home.news;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NewsRuntimeProperties.class)
class NewsRuntimeConfiguration {
}
