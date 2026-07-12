package com.home.admin.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AdminAuthController.class)
@Import({AdminSecurityConfiguration.class, AdminSecurityProblemHandler.class,
    AdminSessionAuthenticationTest.ProbeConfiguration.class})
class AdminSpaCsrfTest {
    @Autowired MockMvc mvc;
    @MockitoBean AdminAuthenticationService authenticationService;

    @Test
    void browserCanEchoXsrfCookieInStandardHeader() throws Exception {
        when(authenticationService.authenticate(anyString(), anyString())).thenReturn(new AdminPrincipal(
            UUID.randomUUID(), "operator", "운영자", Set.of("OPERATOR"), Set.of("COORDINATE_WRITE")));
        var login = mvc.perform(post("/api/v1/admin/auth/login")
                .contentType("application/json")
                .content("{\"loginId\":\"operator\",\"password\":\"password\"}"))
            .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        var cookie = new jakarta.servlet.http.Cookie("XSRF-TOKEN", UUID.randomUUID().toString());

        mvc.perform(post("/api/v1/admin/protected-probe").session(session))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/protected-probe").session(session).cookie(cookie)
                .header("X-XSRF-TOKEN", cookie.getValue()))
            .andExpect(status().isOk());
    }
}
