package com.home.application.ingest.metadata.admin;

import java.util.List;
import java.util.Objects;

import com.home.application.ingest.metadata.admin.MetadataAdminModels.ActionResult;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Alias;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Detail;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Pending;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Summary;

public class MetadataAdminService {
	private final MetadataAdminRepository repository;

	public MetadataAdminService(MetadataAdminRepository repository) {
		this.repository = Objects.requireNonNull(repository);
	}

	public List<Pending> findPending(int limit, int offset) {
		if (limit < 1 || limit > 200 || offset < 0) throw new InvalidMetadataAdminRequestException("invalid page");
		return repository.findPending(limit, offset);
	}
	public Summary summary() { return repository.summary(); }
	public Detail detail(long complexId) { return repository.detail(complexId); }
	public ActionResult retry(long complexId, String actor, String reason) {
		validateDecision(actor, reason); return repository.retry(complexId, actor.trim(), reason.trim());
	}
	public ActionResult hold(long complexId, String actor, String reason) {
		validateDecision(actor, reason); return repository.hold(complexId, actor.trim(), reason.trim());
	}
	public List<Alias> aliases() { return repository.aliases(); }
	public Alias proposeAlias(String canonicalPrefix, String sourcePrefix, String actor, String reason) {
		validatePrefix(canonicalPrefix); validatePrefix(sourcePrefix); validateDecision(actor, reason);
		if (canonicalPrefix.equals(sourcePrefix)) throw new InvalidMetadataAdminRequestException("prefixes must differ");
		return repository.proposeAlias(canonicalPrefix, sourcePrefix, actor.trim(), reason.trim());
	}
	public ActionResult approveAlias(long aliasId, String actor, String reason) {
		validateDecision(actor, reason); return repository.approveAlias(aliasId, actor.trim(), reason.trim());
	}
	public ActionResult disableAlias(long aliasId, String actor, String reason) {
		validateDecision(actor, reason); return repository.disableAlias(aliasId, actor.trim(), reason.trim());
	}
	private void validateDecision(String actor, String reason) {
		if (actor == null || actor.isBlank() || actor.length() > 128 || reason == null || reason.isBlank()
			|| reason.length() > 1000) throw new InvalidMetadataAdminRequestException("actor and reason are required");
	}
	private void validatePrefix(String prefix) {
		if (prefix == null || !prefix.matches("\\d{8}")) throw new InvalidMetadataAdminRequestException("prefix must be 8 digits");
	}
}
