package com.home.nonbatch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component("testOnlyNonBatchFeature")
@ConditionalOnProperty(name = "home.test.non-batch.enabled", havingValue = "true")
public class TestOnlyNonBatchFeature {
}
