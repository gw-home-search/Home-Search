package com.home.user.oauth;

import com.home.application.user.OAuthLoginCommand;
import com.home.application.user.OAuthLoginResult;
import com.home.application.user.OAuthLoginService;
import com.home.domain.user.OAuthProvider;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OAuthLoginFacade {
    private final OAuthLoginService service;

    public OAuthLoginFacade(OAuthLoginService service) {
        this.service = service;
    }

    @Transactional
    public OAuthLoginResult login(OAuthProvider provider, OAuthProfile profile) {
        return service.login(
                new OAuthLoginCommand(provider, profile.providerSubject(), profile.profile(), Instant.now()));
    }
}
