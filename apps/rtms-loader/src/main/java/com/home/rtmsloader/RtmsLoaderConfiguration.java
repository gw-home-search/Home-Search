package com.home.rtmsloader;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RtmsLoaderProperties.class)
class RtmsLoaderConfiguration {

	@Bean
	Clock rtmsLoaderClock() {
		return Clock.systemUTC();
	}

	@Bean
	RtmsLoaderJobPlanner rtmsLoaderJobPlanner(Clock rtmsLoaderClock) {
		return new RtmsLoaderJobPlanner(rtmsLoaderClock);
	}
}
