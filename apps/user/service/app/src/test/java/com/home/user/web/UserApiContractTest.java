package com.home.user.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.auth.RefreshTokenService;
import com.home.application.auth.RotatedRefreshToken;
import com.home.application.auth.port.AccessTokenIssuer;
import com.home.application.user.CurrentUserQueryService;
import com.home.application.user.OAuthLoginResult;
import com.home.domain.user.OAuthProvider;
import com.home.domain.user.UserProfile;
import com.home.user.config.properties.AuthProperties;
import com.home.user.config.properties.CookieProperties;
import com.home.user.cookie.RefreshTokenCookieFactory;
import com.home.user.security.AuthenticatedUserPrincipal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserApiContractTest {
    @Test
    void rotatesRefreshCookieBeforeReturningAccessToken() throws Exception {
        var refresh = mock(RefreshTokenService.class);
        AccessTokenIssuer access = mock(AccessTokenIssuer.class);
        when(refresh.rotate("old"))
                .thenReturn(new RotatedRefreshToken(42, "new", Instant.now().plus(Duration.ofDays(30))));
        when(access.issue(42)).thenReturn("signed-access");
        var mvc = MockMvcBuilders.standaloneSetup(new AuthController(refresh, access, cookieFactory()))
                .build();
        mvc.perform(post("/auth/access").cookie(new jakarta.servlet.http.Cookie("refresh_token", "old")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("signed-access"))
                .andExpect(header().string(
                                HttpHeaders.SET_COOKIE,
                                org.hamcrest.Matchers.allOf(
                                        org.hamcrest.Matchers.containsString("HttpOnly"),
                                                org.hamcrest.Matchers.containsString("Secure"),
                                        org.hamcrest.Matchers.containsString("SameSite=Lax"),
                                                org.hamcrest.Matchers.containsString("Path=/auth"))));
    }

    @Test
    void logoutWithoutCookieStillClearsCookie() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new AuthController(
                        mock(RefreshTokenService.class), mock(AccessTokenIssuer.class), cookieFactory()))
                .build();
        mvc.perform(post("/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(
                                HttpHeaders.SET_COOKIE,
                                org.hamcrest.Matchers.allOf(
                                        org.hamcrest.Matchers.containsString("Max-Age=0"),
                                        org.hamcrest.Matchers.containsString("Path=/auth"))));
    }

    @Test
    void returnsExactMeFieldsWithoutEmail() throws Exception {
        var users = mock(CurrentUserQueryService.class);
        when(users.find(42))
                .thenReturn(new OAuthLoginResult(
                        42, OAuthProvider.GOOGLE, new UserProfile("홍길동", "hidden@example.com", null)));
        var controller = new UserController(users);
        var response = controller.me(new AuthenticatedUserPrincipal(42));
        org.assertj.core.api.Assertions.assertThat(response).isEqualTo(new MeResponse(42, "GOOGLE", "홍길동", null));
        org.assertj.core.api.Assertions.assertThat(response.toString()).doesNotContain("hidden@example.com");
    }

    private RefreshTokenCookieFactory cookieFactory() {
        return new RefreshTokenCookieFactory(
                new CookieProperties(true),
                new AuthProperties(URI.create("https://home.example"), Duration.ofDays(30)),
                new MockEnvironment().withProperty("spring.profiles.active", "prod"));
    }
}
