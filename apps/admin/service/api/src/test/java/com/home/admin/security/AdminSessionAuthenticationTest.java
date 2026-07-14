package com.home.admin.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = AdminAuthController.class)
@Import({
    AdminSecurityConfiguration.class,
    AdminSecurityProblemHandler.class,
    AdminSessionAuthenticationTest.ProbeConfiguration.class
})
class AdminSessionAuthenticationTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    AdminAuthenticationService authenticationService;

    @Test
    void loginSessionAuthenticatesSubsequentProtectedRequest() throws Exception {
        when(authenticationService.authenticate(anyString(), anyString())).thenReturn(principal());

        MvcResult login = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"loginId\":\"operator\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/api/v1/admin/protected-probe").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("operator"));
    }

    @Test
    void protectedMutationRequiresCsrfAfterLogin() throws Exception {
        when(authenticationService.authenticate(anyString(), anyString())).thenReturn(principal());
        MvcResult login = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"loginId\":\"operator\",\"password\":\"password\"}"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(post("/api/v1/admin/protected-probe").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/protected-probe").session(session).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void authenticatedViewerCannotUseWritePermission() throws Exception {
        AdminPrincipal viewer =
                new AdminPrincipal(UUID.randomUUID(), "viewer", "조회자", Set.of("VIEWER"), Set.of("COORDINATE_READ"));
        when(authenticationService.authenticate(anyString(), anyString())).thenReturn(viewer);
        MvcResult login = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"loginId\":\"viewer\",\"password\":\"password\"}"))
                .andReturn();

        mvc.perform(post("/api/v1/admin/protected-probe")
                        .session((MockHttpSession) login.getRequest().getSession(false))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void meRequiresAuthenticatedSecurityContext() throws Exception {
        mvc.perform(get("/api/v1/admin/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void legacySessionAttributeCannotForgeAuthentication() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("adminPrincipal", principal());

        mvc.perform(get("/api/v1/admin/protected-probe").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutInvalidatesSessionImmediately() throws Exception {
        when(authenticationService.authenticate(anyString(), anyString())).thenReturn(principal());
        MvcResult login = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"loginId\":\"operator\",\"password\":\"password\"}"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mvc.perform(post("/api/v1/admin/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/admin/protected-probe").session(session)).andExpect(status().isUnauthorized());
    }

    private AdminPrincipal principal() {
        return new AdminPrincipal(
                UUID.randomUUID(),
                "operator",
                "운영자",
                Set.of("OPERATOR"),
                Set.of("COORDINATE_READ", "COORDINATE_WRITE"));
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/admin/protected-probe")
        @PreAuthorize("hasAuthority('COORDINATE_READ')")
        AdminPrincipal get(@AuthenticationPrincipal AdminPrincipal principal) {
            return principal;
        }

        @org.springframework.web.bind.annotation.PostMapping("/api/v1/admin/protected-probe")
        @PreAuthorize("hasAuthority('COORDINATE_WRITE')")
        AdminPrincipal post(@AuthenticationPrincipal AdminPrincipal principal) {
            return principal;
        }
    }
}
