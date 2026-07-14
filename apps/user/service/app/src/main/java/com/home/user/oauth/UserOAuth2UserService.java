package com.home.user.oauth;

import com.home.domain.user.OAuthProvider;
import java.util.Collection;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

@Component
public class UserOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final OAuthLoginFacade facade;

    public UserOAuth2UserService(OAuthLoginFacade facade) {
        this.facade = facade;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User source = delegate.loadUser(request);
        String id = request.getClientRegistration().getRegistrationId();
        OAuthProvider provider;
        OAuthProfile profile;
        if ("kakao".equals(id)) {
            provider = OAuthProvider.KAKAO;
            profile = OAuthProfileMapping.map(() -> new KakaoOAuth2ProfileMapper().map(source.getAttributes()));
        } else if ("naver".equals(id)) {
            provider = OAuthProvider.NAVER;
            profile = OAuthProfileMapping.map(() -> new NaverOAuth2ProfileMapper().map(source.getAttributes()));
        } else throw new OAuth2AuthenticationException("unsupported_provider");
        long userId = facade.login(provider, profile).userId();
        return new Principal(source, userId);
    }

    private record Principal(OAuth2User delegate, long homeSearchUserId) implements OAuth2User, OAuthAuthenticatedUser {
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
