package com.home.user.oauth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

class OAuthProfileMappingTest {
    @Test
    void convertsInvalidProviderProfileIntoOAuthFailure() {
        assertThatThrownBy(() -> OAuthProfileMapping.map(() -> {
                    throw new IllegalArgumentException("provider subject is required");
                }))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .extracting(error ->
                        ((OAuth2AuthenticationException) error).getError().getErrorCode())
                .isEqualTo("invalid_user_info");
    }
}
