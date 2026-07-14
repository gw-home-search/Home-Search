package com.home.admin.internal;

import com.home.admin.audit.AdminAuditService;
import com.home.admin.security.AdminPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnProperty(name = "home.admin.internal.enabled", havingValue = "true")
public class AdminPropertyBffController {
    private final PropertyAdminClient client;
    private final AdminAuditService audit;

    public AdminPropertyBffController(PropertyAdminClient client, AdminAuditService audit) {
        this.client = client;
        this.audit = audit;
    }

    @GetMapping("/coordinates/pending")
    @PreAuthorize("hasAuthority('COORDINATE_READ')")
    public ResponseEntity<byte[]> coordinatePending(
            @AuthenticationPrincipal AdminPrincipal principal,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return forward(
                principal,
                "COORDINATE_PENDING_READ",
                HttpMethod.GET,
                "/internal/v1/admin/coordinates/pending",
                Map.of("limit", String.valueOf(limit), "offset", String.valueOf(offset)),
                null);
    }

    @GetMapping("/coordinates/pending/summary")
    @PreAuthorize("hasAuthority('COORDINATE_READ')")
    public ResponseEntity<byte[]> coordinateSummary(@AuthenticationPrincipal AdminPrincipal principal) {
        return forward(
                principal,
                "COORDINATE_SUMMARY_READ",
                HttpMethod.GET,
                "/internal/v1/admin/coordinates/pending/summary",
                Map.of(),
                null);
    }

    @PutMapping("/coordinates/{pnu}/override")
    @PreAuthorize("hasAuthority('COORDINATE_WRITE')")
    public ResponseEntity<byte[]> coordinateOverride(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable @Pattern(regexp = "\\d{19}") String pnu,
            @Valid @RequestBody CoordinateOverrideRequest body) {
        return forward(
                principal,
                "COORDINATE_OVERRIDE",
                HttpMethod.PUT,
                "/internal/v1/admin/coordinates/" + pnu + "/override",
                Map.of(),
                body);
    }

    @GetMapping("/metadata/pending")
    @PreAuthorize("hasAuthority('METADATA_READ')")
    public ResponseEntity<byte[]> metadataPending(
            @AuthenticationPrincipal AdminPrincipal principal,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return forward(
                principal,
                "METADATA_PENDING_READ",
                HttpMethod.GET,
                "/internal/v1/admin/metadata/pending",
                Map.of("limit", String.valueOf(limit), "offset", String.valueOf(offset)),
                null);
    }

    @GetMapping("/metadata/pending/summary")
    @PreAuthorize("hasAuthority('METADATA_READ')")
    public ResponseEntity<byte[]> metadataSummary(@AuthenticationPrincipal AdminPrincipal principal) {
        return forward(
                principal,
                "METADATA_SUMMARY_READ",
                HttpMethod.GET,
                "/internal/v1/admin/metadata/pending/summary",
                Map.of(),
                null);
    }

    @GetMapping("/metadata/{complexId}")
    @PreAuthorize("hasAuthority('METADATA_READ')")
    public ResponseEntity<byte[]> metadataDetail(
            @AuthenticationPrincipal AdminPrincipal principal, @PathVariable long complexId) {
        return forward(
                principal,
                "METADATA_DETAIL_READ",
                HttpMethod.GET,
                "/internal/v1/admin/metadata/" + complexId,
                Map.of(),
                null);
    }

    @PostMapping("/metadata/{complexId}/retry")
    @PreAuthorize("hasAuthority('METADATA_RETRY')")
    public ResponseEntity<byte[]> metadataRetry(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable long complexId,
            @Valid @RequestBody DecisionRequest body) {
        return forward(
                principal,
                "METADATA_RETRY",
                HttpMethod.POST,
                "/internal/v1/admin/metadata/" + complexId + "/retry",
                Map.of(),
                body);
    }

    @PostMapping("/metadata/{complexId}/hold")
    @PreAuthorize("hasAuthority('METADATA_HOLD')")
    public ResponseEntity<byte[]> metadataHold(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable long complexId,
            @Valid @RequestBody DecisionRequest body) {
        return forward(
                principal,
                "METADATA_HOLD",
                HttpMethod.POST,
                "/internal/v1/admin/metadata/" + complexId + "/hold",
                Map.of(),
                body);
    }

    @GetMapping("/metadata/pnu-aliases")
    @PreAuthorize("hasAuthority('METADATA_READ')")
    public ResponseEntity<byte[]> aliases(@AuthenticationPrincipal AdminPrincipal principal) {
        return forward(
                principal,
                "METADATA_ALIAS_READ",
                HttpMethod.GET,
                "/internal/v1/admin/metadata/pnu-aliases",
                Map.of(),
                null);
    }

    @PostMapping("/metadata/pnu-aliases")
    @PreAuthorize("hasAuthority('METADATA_ALIAS_MANAGE')")
    public ResponseEntity<byte[]> proposeAlias(
            @AuthenticationPrincipal AdminPrincipal principal, @Valid @RequestBody AliasProposalRequest body) {
        return forward(
                principal,
                "METADATA_ALIAS_PROPOSE",
                HttpMethod.POST,
                "/internal/v1/admin/metadata/pnu-aliases",
                Map.of(),
                body);
    }

    @PostMapping("/metadata/pnu-aliases/{aliasId}/approve")
    @PreAuthorize("hasAuthority('METADATA_ALIAS_MANAGE')")
    public ResponseEntity<byte[]> approveAlias(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable long aliasId,
            @Valid @RequestBody DecisionRequest body) {
        return forward(
                principal,
                "METADATA_ALIAS_APPROVE",
                HttpMethod.POST,
                "/internal/v1/admin/metadata/pnu-aliases/" + aliasId + "/approve",
                Map.of(),
                body);
    }

    @PostMapping("/metadata/pnu-aliases/{aliasId}/disable")
    @PreAuthorize("hasAuthority('METADATA_ALIAS_MANAGE')")
    public ResponseEntity<byte[]> disableAlias(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable long aliasId,
            @Valid @RequestBody DecisionRequest body) {
        return forward(
                principal,
                "METADATA_ALIAS_DISABLE",
                HttpMethod.POST,
                "/internal/v1/admin/metadata/pnu-aliases/" + aliasId + "/disable",
                Map.of(),
                body);
    }

    private ResponseEntity<byte[]> forward(
            AdminPrincipal principal,
            String eventType,
            HttpMethod method,
            String path,
            Map<String, String> query,
            Object body) {
        String requestId = UUID.randomUUID().toString();
        PropertyAdminClient.DownstreamResponse response;
        try {
            response =
                    client.exchange(new PropertyAdminClient.Request(method, path, query, body, principal, requestId));
        } catch (RuntimeException exception) {
            try {
                audit.recordBffRequest(principal.accountId(), requestId, eventType, false);
            } catch (RuntimeException auditException) {
                exception.addSuppressed(auditException);
            }
            throw new PropertyAdminDownstreamException(requestId, exception);
        }
        try {
            audit.recordBffRequest(principal.accountId(), requestId, eventType, response.successful());
        } catch (RuntimeException exception) {
            throw new PropertyAdminDownstreamException(requestId, exception);
        }
        return ResponseEntity.status(response.status())
                .contentType(response.contentType())
                .header("X-Request-Id", requestId)
                .body(response.body());
    }

    public record CoordinateOverrideRequest(
            @NotNull @DecimalMin("33.0") @DecimalMax("39.0") BigDecimal latitude,

            @NotNull @DecimalMin("124.0") @DecimalMax("132.0")
            BigDecimal longitude,

            @NotBlank @Size(max = 1000) String reason) {}

    public record DecisionRequest(
            @NotBlank @Size(max = 1000) String reason) {}

    public record AliasProposalRequest(
            @Pattern(regexp = "\\d{8}") String canonicalPrefix,
            @Pattern(regexp = "\\d{8}") String sourcePrefix,
            @NotBlank @Size(max = 1000) String reason) {}

    public static final class PropertyAdminDownstreamException extends RuntimeException {
        private final String requestId;

        PropertyAdminDownstreamException(String requestId, Throwable cause) {
            super("property admin downstream failed", cause);
            this.requestId = requestId;
        }

        public String requestId() {
            return requestId;
        }
    }
}
