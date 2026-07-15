package com.home.infrastructure.persistence.complex;

import com.home.domain.complex.relation.ComplexRelationClassifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ComplexRelationPersistenceConfiguration {

    @Bean
    ComplexRelationClassifier complexRelationClassifier() {
        return new ComplexRelationClassifier();
    }
}
