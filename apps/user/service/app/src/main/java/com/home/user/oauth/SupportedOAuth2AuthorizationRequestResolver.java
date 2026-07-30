package com.home.user.oauth;

import com.home.user.config.properties.OAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

@Component
public class SupportedOAuth2AuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {
    private static final String BASE_URI = "/oauth2/authorization";
    private static final String UNSET = "UNSET";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final Set<String> enabledProviders;

    public SupportedOAuth2AuthorizationRequestResolver(
            ClientRegistrationRepository registrations, OAuthProperties properties) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, BASE_URI);
        this.enabledProviders = properties.enabledProviders().stream()
                .map(provider -> provider.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        for (String provider : enabledProviders) {
            var registration = registrations.findByRegistrationId(provider);
            if (registration == null
                    || missing(registration.getClientId())
                    || missing(registration.getClientSecret())) {
                throw new IllegalStateException("Enabled OAuth provider credentials are not configured: " + provider);
            }
        }
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        String prefix = BASE_URI + "/";
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith(prefix) && !enabledProviders.contains(path.substring(prefix.length()))) {
            return null;
        }
        return delegate.resolve(request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return enabledProviders.contains(clientRegistrationId) ? delegate.resolve(request, clientRegistrationId) : null;
    }

    private static boolean missing(String credential) {
        return credential == null || credential.isBlank() || UNSET.equals(credential);
    }
}
