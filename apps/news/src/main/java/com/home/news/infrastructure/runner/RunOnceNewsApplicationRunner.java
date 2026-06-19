package com.home.news.infrastructure.runner;

import com.home.news.NewsRuntimeProperties;
import com.home.news.application.OneKeywordNewsCollectionService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

public class RunOnceNewsApplicationRunner implements ApplicationRunner {

	private final OneKeywordNewsCollectionService service;
	private final NewsRuntimeProperties properties;

	public RunOnceNewsApplicationRunner(OneKeywordNewsCollectionService service, NewsRuntimeProperties properties) {
		this.service = service;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.isEnabled()
			|| !properties.getRunOnce().isEnabled()
			|| properties.getRunOnce().getQueryText() == null
			|| properties.getRunOnce().getQueryText().isBlank()) {
			return;
		}
		service.collect(properties.getRunOnce().getQueryText());
	}
}
