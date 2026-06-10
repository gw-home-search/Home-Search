package com.home.infrastructure.persistence.news;

import java.util.Map;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class StandaloneNewsStageCondition implements Condition {

	private static final String PIPELINE_ENABLED_PROPERTY = "home.news.pipeline.enabled";

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		Map<String, Object> attributes = metadata.getAnnotationAttributes(
			ConditionalOnStandaloneNewsStage.class.getName()
		);
		if (attributes == null) {
			return false;
		}
		String enabledProperty = (String) attributes.get("enabledProperty");
		boolean stageEnabled = context.getEnvironment().getProperty(enabledProperty, Boolean.class, false);
		boolean pipelineEnabled = context.getEnvironment().getProperty(
			PIPELINE_ENABLED_PROPERTY,
			Boolean.class,
			false
		);
		return stageEnabled && !pipelineEnabled;
	}
}
