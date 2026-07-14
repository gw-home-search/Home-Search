package com.home.user.web;

import com.home.application.auth.RefreshTokenService;
import com.home.application.auth.port.AccessTokenIssuer;
import com.home.user.cookie.RefreshTokenCookieFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final RefreshTokenService refresh;
    private final AccessTokenIssuer access;
    private final RefreshTokenCookieFactory cookies;

    public AuthController(RefreshTokenService refresh, AccessTokenIssuer access, RefreshTokenCookieFactory cookies) {
        this.refresh = refresh;
        this.access = access;
        this.cookies = cookies;
    }

    @PostMapping("/auth/access")
    ResponseEntity<AccessTokenResponse> access(@CookieValue(name = "refresh_token", required = false) String raw) {
        var rotated = refresh.rotate(raw);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookies.active(rotated.rawToken()).toString())
                .body(new AccessTokenResponse(access.issue(rotated.userId())));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(@CookieValue(name = "refresh_token", required = false) String raw) {
        refresh.revoke(raw);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.expired().toString())
                .build();
    }
}
