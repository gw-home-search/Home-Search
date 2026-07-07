package com.home.domain.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IngestSourceIdentityTest {

	@Test
	@DisplayName("ingest source identity는 source와 sourceKey를 trim한다")
	void trimsSourceIdentity() {
		IngestSource source = IngestSource.of(" RTMS ");
		IngestSourceKey sourceKey = IngestSourceKey.of(" key-1 ");

		assertThat(source.value()).isEqualTo("RTMS");
		assertThat(sourceKey.value()).isEqualTo("key-1");
	}

	@Test
	@DisplayName("ingest source identity는 빈 source와 sourceKey를 거부한다")
	void rejectsBlankSourceIdentity() {
		assertThatThrownBy(() -> IngestSource.of(" "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("source is required");

		assertThatThrownBy(() -> IngestSourceKey.of(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("sourceKey is required");
	}
}
