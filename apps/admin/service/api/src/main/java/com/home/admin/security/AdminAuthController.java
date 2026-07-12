package com.home.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/auth")
public class AdminAuthController {
    private final AdminAuthenticationService authentication;
    private final SecurityContextRepository securityContextRepository;
    public AdminAuthController(AdminAuthenticationService authentication,
                               SecurityContextRepository securityContextRepository) {
        this.authentication = authentication;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/login")
    public AdminPrincipal login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                                HttpServletResponse response) {
        AdminPrincipal principal = authentication.authenticate(body.loginId(), body.password());
        request.getSession(true);
        request.changeSessionId();
        var authorities = java.util.stream.Stream.concat(
                principal.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)),
                principal.permissions().stream().map(SimpleGrantedAuthority::new))
            .toList();
        var springAuthentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(springAuthentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return principal;
    }
    @GetMapping("/me")
    public AdminPrincipal me(@AuthenticationPrincipal AdminPrincipal principal) {
        return principal;
    }
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AdminAuthenticationService.InvalidCredentialsException.class)
    ResponseEntity<ProblemDetail> invalid() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "로그인 정보를 확인하세요.");
        return ResponseEntity.status(401).body(detail);
    }
    public record LoginRequest(@NotBlank @Size(max=100) String loginId, @NotBlank @Size(max=200) String password) {}
}
