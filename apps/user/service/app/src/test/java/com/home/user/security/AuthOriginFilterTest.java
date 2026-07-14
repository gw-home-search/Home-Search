package com.home.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.user.config.properties.AuthProperties;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthOriginFilterTest {
    @Test
    void rejectsMissingOriginOnRefreshMutationWithTheCommonProblemShape() throws Exception {
        var request = new MockHttpServletRequest("POST", "/auth/access");
        var response = new MockHttpServletResponse();

        new AuthOriginFilter(new AuthProperties(URI.create("https://home.example"), Duration.ofDays(30)))
                .doFilter(request, response, new MockFilterChain());

        var body = response.getContentAsString();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body).contains("\"type\":\"/docs/index.html#error-code-list\"");
        assertThat(body).contains("\"detail\":");
        assertThat(body).contains("\"exception\":\"AuthOriginException\"");
        assertThat(body).contains("\"timestamp\":");
        assertThat(body).contains("\"code\":\"AUTH_ORIGIN_REJECTED\"");
    }
}
