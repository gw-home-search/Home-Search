package com.home.application.user;

import com.home.application.user.port.IdentityLock;
import com.home.application.user.port.UserRepository;
import com.home.domain.user.OAuthIdentityKey;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OAuthLoginService {
    private final UserRepository repository;
    private final IdentityLock identityLock;

    public OAuthLoginService(UserRepository repository, IdentityLock identityLock) {
        this.repository = Objects.requireNonNull(repository);
        this.identityLock = Objects.requireNonNull(identityLock);
    }

    @Transactional
    public OAuthLoginResult login(OAuthLoginCommand command) {
        var identity = new OAuthIdentityKey(command.provider(), command.providerSubject());
        identityLock.lock(identity);
        return repository
                .findByIdentity(identity)
                .map(current -> repository.updateProfile(
                        identity, current.profile().merge(command.profile()), command.loginAt()))
                .orElseGet(() -> repository.create(identity, command.profile().forNewUser(), command.loginAt()));
    }
}
