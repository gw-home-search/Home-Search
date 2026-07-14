package com.home.admin.security;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.admin.AdminApiExceptionHandler;
import com.home.admin.account.AdminAccountController;
import com.home.admin.account.AdminAccountService;
import com.home.admin.audit.AdminAuditController;
import com.home.admin.audit.AdminAuditService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {AdminAccountController.class, AdminAuditController.class})
@Import({AdminSecurityConfiguration.class, AdminSecurityProblemHandler.class, AdminApiExceptionHandler.class})
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

    private UsernamePasswordAuthenticationToken authenticationFor(String role, String permission) {
        AdminPrincipal principal =
                new AdminPrincipal(UUID.randomUUID(), "actor", "관리자", Set.of(role), Set.of(permission));
        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role), new SimpleGrantedAuthority(permission)));
    }
}
