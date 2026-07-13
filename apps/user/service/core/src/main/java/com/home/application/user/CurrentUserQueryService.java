package com.home.application.user;

import com.home.application.user.port.UserRepository;
import java.util.Objects;

public final class CurrentUserQueryService {
    private final UserRepository repository;
    public CurrentUserQueryService(UserRepository repository) { this.repository = Objects.requireNonNull(repository); }
    public OAuthLoginResult find(long userId) { return repository.findByUserId(userId).orElseThrow(UserNotFoundException::new); }
}
