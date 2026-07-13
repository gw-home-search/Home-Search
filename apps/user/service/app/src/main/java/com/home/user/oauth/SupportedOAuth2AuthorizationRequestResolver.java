package com.home.user.oauth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

@Component
public class SupportedOAuth2AuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {
    private static final String BASE_URI = "/oauth2/authorization";
    private static final Set<String> SUPPORTED = Set.of("google", "kakao", "naver");

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public SupportedOAuth2AuthorizationRequestResolver(ClientRegistrationRepository registrations) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, BASE_URI);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        String prefix = BASE_URI + "/";
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith(prefix) && !SUPPORTED.contains(path.substring(prefix.length()))) {
            return null;
        }
        return delegate.resolve(request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return SUPPORTED.contains(clientRegistrationId) ? delegate.resolve(request, clientRegistrationId) : null;
    }
}
