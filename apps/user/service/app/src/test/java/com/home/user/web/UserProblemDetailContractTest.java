package com.home.user.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class UserProblemDetailContractTest {
    @Test
    void authenticationErrorUsesTheCommonProblemDetailShape() {
        var detail = new UserApiExceptionHandler().unauthorized();

        assertThat(detail.getType().toString()).isEqualTo("/docs/index.html#error-code-list");
        assertThat(detail.getTitle()).isEqualTo("인증이 필요합니다");
        assertThat(detail.getStatus()).isEqualTo(401);
        assertThat(detail.getDetail()).isEqualTo("Authentication is required.");
        assertThat(detail.getProperties()).containsEntry("code", "AUTHENTICATION_REQUIRED");
        assertThat(detail.getProperties()).containsEntry("exception", "AuthenticationException");
        assertThat(detail.getProperties().get("timestamp")).isInstanceOf(OffsetDateTime.class);
    }
}
