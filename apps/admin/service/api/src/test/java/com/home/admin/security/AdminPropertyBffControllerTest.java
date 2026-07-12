package com.home.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.home.admin.AdminApiExceptionHandler;
import com.home.admin.audit.AdminAuditService;
import com.home.admin.internal.AdminPropertyBffController;
import com.home.admin.internal.PropertyAdminClient;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminPropertyBffController.class)
@TestPropertySource(properties = "home.admin.internal.enabled=true")
@Import({AdminSecurityConfiguration.class, AdminSecurityProblemHandler.class, AdminApiExceptionHandler.class})
class AdminPropertyBffControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PropertyAdminClient client;
    @MockitoBean AdminAuditService audit;

    @Test
    void bffRequiresSessionAndWritePermission() throws Exception {
        mvc.perform(get("/api/v1/admin/coordinates/pending")).andExpect(status().isUnauthorized());

        mvc.perform(put("/api/v1/admin/coordinates/1168010300101400001/override")
                .with(authentication(auth("VIEWER", "COORDINATE_READ"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":37.5,\"longitude\":127.0,\"reason\":\"verified\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void preservesDownstreamConflictAndAuditsServerGeneratedRequestId() throws Exception {
        byte[] problem = "{\"status\":409,\"detail\":\"conflict\"}".getBytes(StandardCharsets.UTF_8);
        given(client.exchange(any())).willReturn(new PropertyAdminClient.DownstreamResponse(
            409, MediaType.APPLICATION_PROBLEM_JSON, problem));

        mvc.perform(put("/api/v1/admin/coordinates/1168010300101400001/override")
                .with(authentication(auth("OPERATOR", "COORDINATE_WRITE"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"latitude":37.5,"longitude":127.0,"reason":"verified","approvedBy":"forged"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(content().bytes(problem))
            .andExpect(header().exists("X-Request-Id"));

        ArgumentCaptor<PropertyAdminClient.Request> request = ArgumentCaptor.forClass(PropertyAdminClient.Request.class);
        verify(client).exchange(request.capture());
        assertThat(request.getValue().method()).isEqualTo(HttpMethod.PUT);
        assertThat(request.getValue().path()).endsWith("/override");
        assertThat(request.getValue().body()).isInstanceOf(AdminPropertyBffController.CoordinateOverrideRequest.class);
        assertThat(request.getValue().body().toString()).doesNotContain("forged").doesNotContain("approvedBy");
        verify(audit).recordBffRequest(request.getValue().principal().accountId(), request.getValue().requestId(),
            "COORDINATE_OVERRIDE", false);
    }

    @Test
    void auditFailureDoesNotAttemptDuplicateAuditWrite() throws Exception {
        given(client.exchange(any())).willReturn(new PropertyAdminClient.DownstreamResponse(
            200, MediaType.APPLICATION_JSON, "{}".getBytes(StandardCharsets.UTF_8)));
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable"))
            .when(audit).recordBffRequest(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());

        mvc.perform(get("/api/v1/admin/coordinates/pending")
                .with(authentication(auth("VIEWER", "COORDINATE_READ"))))
            .andExpect(status().isBadGateway())
            .andExpect(header().exists("X-Request-Id"));

        verify(audit, times(1)).recordBffRequest(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    private UsernamePasswordAuthenticationToken auth(String role, String... permissions) {
        AdminPrincipal principal = new AdminPrincipal(UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "operator", "운영자", Set.of(role), Set.of(permissions));
        List<SimpleGrantedAuthority> authorities = java.util.stream.Stream.concat(
            java.util.stream.Stream.of(new SimpleGrantedAuthority("ROLE_" + role)),
            principal.permissions().stream().map(SimpleGrantedAuthority::new)).toList();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }
}
