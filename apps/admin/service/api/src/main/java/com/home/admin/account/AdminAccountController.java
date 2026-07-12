package com.home.admin.account;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.home.admin.security.AdminPrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/accounts")
@PreAuthorize("hasAuthority('ADMIN_ACCOUNT_MANAGE')")
public class AdminAccountController {
    private final AdminAccountService service;
    public AdminAccountController(AdminAccountService service) { this.service = service; }

    @GetMapping
    public List<AdminAccountService.AccountSummary> accounts() { return service.accounts(); }

    @PostMapping
    public ResponseEntity<AdminAccountService.AccountSummary> create(
            @AuthenticationPrincipal AdminPrincipal actor, @Valid @RequestBody CreateAccountRequest request) {
        var created = service.create(actor.accountId(), new AdminAccountService.CreateAccount(
            request.loginId(), request.displayName(), request.password(), request.roles()));
        return ResponseEntity.created(URI.create("/api/v1/admin/accounts/" + created.accountId())).body(created);
    }

    @PutMapping("/{accountId}/roles")
    public ResponseEntity<Void> replaceRoles(@AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable UUID accountId, @Valid @RequestBody RoleRequest request) {
        service.replaceRoles(actor.accountId(), accountId, request.roles());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{accountId}/status")
    public ResponseEntity<Void> status(@AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable UUID accountId, @RequestBody StatusRequest request) {
        service.setEnabled(actor.accountId(), accountId, request.enabled());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{accountId}/sessions")
    public ResponseEntity<Void> revokeSessions(@AuthenticationPrincipal AdminPrincipal actor, @PathVariable UUID accountId) {
        service.revokeSessions(actor.accountId(), accountId);
        return ResponseEntity.noContent().build();
    }

    public record CreateAccountRequest(@NotBlank @Size(max=100) String loginId,
                                       @NotBlank @Size(max=100) String displayName,
                                       @NotBlank @Size(min=12, max=200) String password,
                                       @NotEmpty Set<String> roles) {}
    public record RoleRequest(@NotEmpty Set<String> roles) {}
    public record StatusRequest(boolean enabled) {}
}
