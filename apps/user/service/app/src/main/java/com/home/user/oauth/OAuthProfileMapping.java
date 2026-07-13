package com.home.user.oauth;

import java.util.function.Supplier;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

public final class OAuthProfileMapping {
    private OAuthProfileMapping() { }

    public static OAuthProfile map(Supplier<OAuthProfile> mapping) {
        try {
            return mapping.get();
        } catch (OAuth2AuthenticationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info"));
        }
    }
}
