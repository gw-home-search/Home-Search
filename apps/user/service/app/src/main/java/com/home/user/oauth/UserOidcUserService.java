package com.home.user.oauth;

import com.home.domain.user.OAuthProvider;
import java.util.Collection;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

@Component
public class UserOidcUserService extends OidcUserService {
    private final OAuthLoginFacade facade;

    public UserOidcUserService(OAuthLoginFacade facade) {
        this.facade = facade;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser source = super.loadUser(request);
        if (!"google".equals(request.getClientRegistration().getRegistrationId()))
            throw new OAuth2AuthenticationException("unsupported_provider");
        OAuthProfile profile = OAuthProfileMapping.map(() -> new GoogleOidcProfileMapper().map(source.getClaims()));
        long userId = facade.login(OAuthProvider.GOOGLE, profile).userId();
        return new Principal(source, userId);
    }

    private record Principal(OidcUser delegate, long homeSearchUserId) implements OidcUser, OAuthAuthenticatedUser {
        @Override
        public Map<String, Object> getClaims() {
            return delegate.getClaims();
        }

        @Override
        public OidcUserInfo getUserInfo() {
            return delegate.getUserInfo();
        }

        @Override
        public OidcIdToken getIdToken() {
            return delegate.getIdToken();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return delegate.getAttributes();
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return delegate.getAuthorities();
        }

        @Override
        public String getName() {
            return delegate.getName();
        }
    }
}
