package com.home.application.user;

import com.home.application.user.port.UserRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentUserQueryService {
    private final UserRepository repository;

    public CurrentUserQueryService(UserRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Transactional(readOnly = true)
    public OAuthLoginResult find(long userId) {
        return repository.findByUserId(userId).orElseThrow(UserNotFoundException::new);
    }
}
