package com.home.infrastructure.persistence.news;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({
	NewsObservationPersistenceConfiguration.class,
	NewsRelevancePersistenceConfiguration.class,
	NewsSignalPersistenceConfiguration.class,
	NewsObsidianExportPersistenceConfiguration.class
})
class NewsPersistenceConfiguration {
}
