package com.home.infrastructure.web.admin;

import java.util.List;

import com.home.application.ingest.metadata.admin.MetadataAdminModels.ActionResult;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Alias;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Detail;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Pending;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Summary;
import com.home.application.ingest.metadata.admin.MetadataAdminService;

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
@RequestMapping("/api/v1/admin/metadata")
@ConditionalOnProperty(name = "home.admin.metadata-enrichment.enabled", havingValue = "true")
public class MetadataAdminController {
	private final MetadataAdminService service;

	public MetadataAdminController(MetadataAdminService service) {
		this.service = service;
	}

	@GetMapping("/pending")
	public List<Pending> pending(@RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
		return service.findPending(limit, offset);
	}
	@GetMapping("/pending/summary")
	public Summary summary() { return service.summary(); }
	@GetMapping("/{complexId}")
	public Detail detail(@PathVariable long complexId) { return service.detail(complexId); }
	@PostMapping("/{complexId}/retry")
	public ActionResult retry(@PathVariable long complexId, @Valid @RequestBody DecisionRequest request) {
		return service.retry(complexId, request.actor(), request.reason());
	}
	@PostMapping("/{complexId}/hold")
	public ActionResult hold(@PathVariable long complexId, @Valid @RequestBody DecisionRequest request) {
		return service.hold(complexId, request.actor(), request.reason());
	}
	@GetMapping("/pnu-aliases")
	public List<Alias> aliases() { return service.aliases(); }
	@PostMapping("/pnu-aliases")
	public Alias proposeAlias(@Valid @RequestBody AliasProposalRequest request) {
		return service.proposeAlias(request.canonicalPrefix(), request.sourcePrefix(), request.actor(), request.reason());
	}
	@PostMapping("/pnu-aliases/{aliasId}/approve")
	public ActionResult approveAlias(@PathVariable long aliasId, @Valid @RequestBody DecisionRequest request) {
		return service.approveAlias(aliasId, request.actor(), request.reason());
	}
	@PostMapping("/pnu-aliases/{aliasId}/disable")
	public ActionResult disableAlias(@PathVariable long aliasId, @Valid @RequestBody DecisionRequest request) {
		return service.disableAlias(aliasId, request.actor(), request.reason());
	}

	public record DecisionRequest(@NotBlank @Size(max=128) String actor, @NotBlank @Size(max=1000) String reason) {}
	public record AliasProposalRequest(
		@Pattern(regexp="\\d{8}") String canonicalPrefix,
		@Pattern(regexp="\\d{8}") String sourcePrefix,
		@NotBlank @Size(max=128) String actor,
		@NotBlank @Size(max=1000) String reason
	) {}
}
