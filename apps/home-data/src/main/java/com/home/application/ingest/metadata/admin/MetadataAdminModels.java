package com.home.application.ingest.metadata.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.home.domain.complex.metadata.OdcloudPnuAliasStatus;

public final class MetadataAdminModels {
	private MetadataAdminModels() {}

	public record Pending(
		long complexId, String aptName, String aptSeq, String canonicalPnu, String address,
		String status, String failureKind, String failureReason, int attempts,
		OffsetDateTime nextAttemptAt, OffsetDateTime holdAt, String holdReason
	) {}

	public record Summary(long totalCount, Map<String, Long> statusCounts) {}

	public record Attempt(
		int attemptNo, String status, String source, String failureKind, String failureReason,
		String lookupPath, String requestedPnu, String resolvedSourcePnu, Long aliasId,
		Integer candidateCount, OffsetDateTime nextAttemptAt, OffsetDateTime observedAt
	) {}

	public record Decision(String decisionType, String actor, String reason, OffsetDateTime createdAt) {}

	public record Detail(Pending complex, List<Attempt> attempts, List<Decision> decisions) {}

	public record Alias(
		long id, String canonicalPrefix, String sourcePrefix, OdcloudPnuAliasStatus status, String reason,
		String approvedBy, OffsetDateTime approvedAt, String disabledBy, OffsetDateTime disabledAt
	) {}

	public record ActionResult(boolean updated) {}
}
