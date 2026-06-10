package com.home.infrastructure.persistence.news;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Conditional(StandaloneNewsStageCondition.class)
@interface ConditionalOnStandaloneNewsStage {

	String enabledProperty();
}
