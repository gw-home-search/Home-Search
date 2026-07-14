package com.home.user.web;

import com.home.application.user.CurrentUserQueryService;
import com.home.user.security.AuthenticatedUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {
    private final CurrentUserQueryService users;

    public UserController(CurrentUserQueryService users) {
        this.users = users;
    }

    @GetMapping("/api/v1/users/me")
    MeResponse me(@AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return MeResponse.from(users.find(principal.userId()));
    }
}
