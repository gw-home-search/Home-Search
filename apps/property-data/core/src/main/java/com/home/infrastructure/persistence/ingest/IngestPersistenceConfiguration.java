package com.home.infrastructure.persistence.ingest;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({
    CoordinateSourcePersistenceConfiguration.class,
    RawIngestPersistenceConfiguration.class,
    TradeMatchPersistenceConfiguration.class,
    TradeNormalizationPersistenceConfiguration.class
})
public class IngestPersistenceConfiguration {}
