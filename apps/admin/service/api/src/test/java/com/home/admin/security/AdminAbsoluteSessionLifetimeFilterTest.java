package com.home.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class AdminAbsoluteSessionLifetimeFilterTest {
    @Test
    void rejectsSessionOlderThanEightHours() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(session.getCreationTime()).plus(Duration.ofHours(8)), ZoneOffset.UTC);
        var filter = new AdminAbsoluteSessionLifetimeFilter(Duration.ofHours(8), clock);
        var request = new MockHttpServletRequest();
        request.setSession(session);
        var response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/problem+json;charset=UTF-8");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void allowsSessionWithinAbsoluteLifetime() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(session.getCreationTime()).plus(Duration.ofMinutes(30)), ZoneOffset.UTC);
        var filter = new AdminAbsoluteSessionLifetimeFilter(Duration.ofHours(8), clock);
        var request = new MockHttpServletRequest();
        request.setSession(session);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(session.isInvalid()).isFalse();
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void allowsLoginToReplaceExpiredSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(session.getCreationTime()).plus(Duration.ofHours(9)), ZoneOffset.UTC);
        var filter = new AdminAbsoluteSessionLifetimeFilter(Duration.ofHours(8), clock);
        var request = new MockHttpServletRequest("POST", "/api/v1/admin/auth/login");
        request.setSession(session);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(session.isInvalid()).isFalse();
        assertThat(chain.getRequest()).isSameAs(request);
    }
}
