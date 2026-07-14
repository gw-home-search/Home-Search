package com.home.domain.ingest.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExecutionCorrelationIdTest {

    @Test
    @DisplayName("execution correlation id는 canonical UUID 문자열만 허용한다")
    void acceptsCanonicalUuidOnly() {
        ExecutionCorrelationId id = ExecutionCorrelationId.from("123e4567-e89b-12d3-a456-426614174000");

        assertThat(id.toString()).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        assertThatThrownBy(() -> ExecutionCorrelationId.from("123E4567-E89B-12D3-A456-426614174000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionCorrelationId.from("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
