package com.home.admin.security;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.admin.AdminApiExceptionHandler;
import com.home.admin.AdminProblemFactory;
import com.home.admin.account.AdminAccountController;
import com.home.admin.account.AdminAccountService;
import com.home.admin.audit.AdminAuditController;
import com.home.admin.audit.AdminAuditService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {AdminAccountController.class, AdminAuditController.class})
@Import({
    AdminSecurityConfiguration.class,
    AdminSecurityProblemHandler.class,
    AdminApiExceptionHandler.class,
    AdminProblemFactory.class
})
class AdminAccountAuthorizationTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    AdminAccountService accountService;

    @MockitoBean
    AdminAuditService auditService;

    @Test
    void operatorCannotReadAccounts() throws Exception {
        mvc.perform(get("/api/v1/admin/accounts")
                        .with(authentication(authenticationFor("OPERATOR", "COORDINATE_READ"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountManagerCanReadAccounts() throws Exception {
        when(accountService.accounts()).thenReturn(List.of());
        mvc.perform(get("/api/v1/admin/accounts")
                        .with(authentication(authenticationFor("ADMIN", "ADMIN_ACCOUNT_MANAGE"))))
                .andExpect(status().isOk());
    }

    @Test
    void accountMutationRequiresCsrf() throws Exception {
        var authentication = authenticationFor("ADMIN", "ADMIN_ACCOUNT_MANAGE");
        String body =
                "{\"loginId\":\"viewer\",\"displayName\":\"조회자\",\"password\":\"long-test-password\",\"roles\":[\"VIEWER\"]}";
        mvc.perform(post("/api/v1/admin/accounts")
                        .with(authentication(authentication))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditReaderCanReadBoundedAuditPage() throws Exception {
        when(auditService.events(50, 0)).thenReturn(List.of());
        mvc.perform(get("/api/v1/admin/audit").with(authentication(authenticationFor("ADMIN", "ADMIN_AUDIT_READ"))))
                .andExpect(status().isOk());
    }

    @Test
    void accountManagerCanCreateAndMaintainAccounts() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        var authentication = authenticationFor(actorId, "ADMIN", "ADMIN_ACCOUNT_MANAGE");
        when(accountService.create(org.mockito.ArgumentMatchers.eq(actorId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AdminAccountService.AccountSummary(
                        accountId, "viewer", "조회자", true, null, Set.of("VIEWER")));

        mvc.perform(
                        post("/api/v1/admin/accounts")
                                .with(authentication(authentication))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        "{\"loginId\":\"viewer\",\"displayName\":\"조회자\",\"password\":\"long-test-password\",\"roles\":[\"VIEWER\"]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/accounts/" + accountId));
        mvc.perform(put("/api/v1/admin/accounts/" + accountId + "/roles")
                        .with(authentication(authentication))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"roles\":[\"OPERATOR\"]}"))
                .andExpect(status().isNoContent());
        mvc.perform(patch("/api/v1/admin/accounts/" + accountId + "/status")
                        .with(authentication(authentication))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/admin/accounts/" + accountId + "/sessions")
                        .with(authentication(authentication))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    private UsernamePasswordAuthenticationToken authenticationFor(String role, String permission) {
        return authenticationFor(UUID.randomUUID(), role, permission);
    }

    private UsernamePasswordAuthenticationToken authenticationFor(UUID accountId, String role, String permission) {
        AdminPrincipal principal = new AdminPrincipal(accountId, "actor", "관리자", Set.of(role), Set.of(permission));
        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role), new SimpleGrantedAuthority(permission)));
    }
}
