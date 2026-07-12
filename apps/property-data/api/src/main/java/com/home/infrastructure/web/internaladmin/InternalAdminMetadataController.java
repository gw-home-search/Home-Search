package com.home.infrastructure.web.internaladmin;

import java.util.List;

import com.home.application.ingest.metadata.admin.MetadataAdminModels.ActionResult;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Alias;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Detail;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Pending;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Summary;
import com.home.application.ingest.metadata.admin.MetadataAdminService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/admin/metadata")
@ConditionalOnProperty(name = "home.admin.internal.enabled", havingValue = "true")
public class InternalAdminMetadataController {
    private final MetadataAdminService service;

    public InternalAdminMetadataController(MetadataAdminService service) { this.service = service; }

    @GetMapping("/pending")
    public List<Pending> pending(@RequestParam(defaultValue = "50") int limit,
                                 @RequestParam(defaultValue = "0") int offset,
                                 HttpServletRequest request) {
        require(request, "METADATA_READ");
        return service.findPending(limit, offset);
    }

    @GetMapping("/pending/summary")
    public Summary summary(HttpServletRequest request) { require(request, "METADATA_READ"); return service.summary(); }

    @GetMapping("/{complexId}")
    public Detail detail(@PathVariable long complexId, HttpServletRequest request) {
        require(request, "METADATA_READ"); return service.detail(complexId);
    }

    @PostMapping("/{complexId}/retry")
    public ActionResult retry(@PathVariable long complexId, @Valid @RequestBody DecisionRequest body,
                              HttpServletRequest request) {
        InternalAdminPrincipal principal = require(request, "METADATA_RETRY");
        return service.retry(complexId, principal.actor(), body.reason());
    }

    @PostMapping("/{complexId}/hold")
    public ActionResult hold(@PathVariable long complexId, @Valid @RequestBody DecisionRequest body,
                             HttpServletRequest request) {
        InternalAdminPrincipal principal = require(request, "METADATA_HOLD");
        return service.hold(complexId, principal.actor(), body.reason());
    }

    @GetMapping("/pnu-aliases")
    public List<Alias> aliases(HttpServletRequest request) { require(request, "METADATA_READ"); return service.aliases(); }

    @PostMapping("/pnu-aliases")
    public Alias proposeAlias(@Valid @RequestBody AliasProposalRequest body, HttpServletRequest request) {
        InternalAdminPrincipal principal = require(request, "METADATA_ALIAS_MANAGE");
        return service.proposeAlias(body.canonicalPrefix(), body.sourcePrefix(), principal.actor(), body.reason());
    }

    @PostMapping("/pnu-aliases/{aliasId}/approve")
    public ActionResult approveAlias(@PathVariable long aliasId, @Valid @RequestBody DecisionRequest body,
                                     HttpServletRequest request) {
        InternalAdminPrincipal principal = require(request, "METADATA_ALIAS_MANAGE");
        return service.approveAlias(aliasId, principal.actor(), body.reason());
    }

    @PostMapping("/pnu-aliases/{aliasId}/disable")
    public ActionResult disableAlias(@PathVariable long aliasId, @Valid @RequestBody DecisionRequest body,
                                     HttpServletRequest request) {
        InternalAdminPrincipal principal = require(request, "METADATA_ALIAS_MANAGE");
        return service.disableAlias(aliasId, principal.actor(), body.reason());
    }

    private InternalAdminPrincipal require(HttpServletRequest request, String permission) {
        InternalAdminPrincipal principal = InternalAdminPrincipal.from(request);
        principal.require(permission);
        return principal;
    }

    public record DecisionRequest(@NotBlank @Size(max = 1000) String reason) {}
    public record AliasProposalRequest(
        @Pattern(regexp = "\\d{8}") String canonicalPrefix,
        @Pattern(regexp = "\\d{8}") String sourcePrefix,
        @NotBlank @Size(max = 1000) String reason
    ) {}
}
