package com.home.user.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OAuthProviderFallbackController {
    @GetMapping("/oauth2/authorization/{provider}")
    ProblemDetail unsupported(@PathVariable String provider) {
        return UserProblemDetails.create(
                HttpStatus.NOT_FOUND,
                "지원하지 않는 OAuth provider입니다",
                "The requested OAuth provider is not supported.",
                "OAUTH_PROVIDER_NOT_SUPPORTED",
                "OAuthProviderNotSupportedException");
    }
}
