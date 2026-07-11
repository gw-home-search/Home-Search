package com.home.application.ingest.buildingmetadata;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.home.domain.complex.buildingmetadata.BuildingMetadataSourceKind;
import com.home.domain.complex.metadata.ComplexMetadataFailureKind;
import com.home.domain.complex.metadata.ComplexMetadataStatus;

public class BuildingMetadataBatchService {
	private final BuildingMetadataEvidenceRepository repository;
	private final BuildingMetadataSourceClient client;
	private final BuildingMetadataSourceParser parser;

	public BuildingMetadataBatchService(BuildingMetadataEvidenceRepository repository,
		BuildingMetadataSourceClient client, BuildingMetadataSourceParser parser) {
		this.repository = Objects.requireNonNull(repository);
		this.client = Objects.requireNonNull(client);
		this.parser = Objects.requireNonNull(parser);
	}

	public BuildingMetadataBatchSummary collect(String mode, int maxRequests, Long fromComplexId, Long toComplexId,
		UUID requestId) {
		if (!List.of("missing", "retry").contains(mode)) throw new IllegalArgumentException("mode must be missing or retry");
		if (maxRequests <= 0) throw new IllegalArgumentException("maxRequests must be positive");
		if (!client.isConfigured()) throw new IllegalStateException("BLD_SERVICE_KEY is required");
		int requests = 0, targets = 0, resolved = 0, review = 0, failed = 0;
		for (BuildingMetadataTarget target : repository.findTargets(mode, maxRequests, fromComplexId, toComplexId)) {
			if (targets >= maxRequests) break;
			targets++;
			BuildingMetadataAttemptResult result;
			if (target.pnuComplexCount() != 1) {
				result = repository.recordAmbiguousPnu(target, requestId);
			}
			else {
				BuildingMetadataSourceKind primary = target.currentValues().dongCnt() != null
					&& target.currentValues().dongCnt() == 1 ? BuildingMetadataSourceKind.BLD_TITLE
					: BuildingMetadataSourceKind.BLD_RECAP_TITLE;
				FetchResult fetched = fetch(target, primary, requestId);
				requests++;
				if (fetched.empty() && requests < maxRequests) {
					BuildingMetadataSourceKind fallback = primary == BuildingMetadataSourceKind.BLD_TITLE
						? BuildingMetadataSourceKind.BLD_RECAP_TITLE : BuildingMetadataSourceKind.BLD_TITLE;
					fetched = fetch(target, fallback, requestId);
					requests++;
				}
				result = fetched.result() != null ? fetched.result() : repository.recordFailure(target, fetched.source(),
					ComplexMetadataStatus.UNAVAILABLE, ComplexMetadataFailureKind.SOURCE_MISSING,
					"source candidate unavailable", requestId, Instant.now().plusSeconds(30L * 86_400));
			}
			if (result.status().isResolvedLike()) resolved++;
			else if (result.status().isFailed()) failed++;
			else review++;
		}
		return new BuildingMetadataBatchSummary(targets, requests, resolved, review, failed);
	}

	private FetchResult fetch(BuildingMetadataTarget target, BuildingMetadataSourceKind source, UUID requestId) {
		try {
			BuildingMetadataSourceResponse response = client.fetch(source, target.pnu());
			if (response.httpStatus() == null || response.httpStatus() < 200 || response.httpStatus() >= 300) {
				return failure(target, source, requestId, "building source HTTP failure");
			}
			ParsedBuildingMetadataSource parsed = parser.parse(response);
			if (parsed.totalCount() > 100) {
				return new FetchResult(repository.recordFailure(target, source, ComplexMetadataStatus.AMBIGUOUS,
					ComplexMetadataFailureKind.AMBIGUOUS, "source totalCount exceeds 100", requestId, null), false, source);
			}
			if (parsed.candidates().isEmpty()) {
				return new FetchResult(null, true, source);
			}
			return new FetchResult(repository.apply(target, source, parsed, requestId), false, source);
		}
		catch (RuntimeException exception) {
			return failure(target, source, requestId, "building source request or parsing failed");
		}
	}

	private FetchResult failure(BuildingMetadataTarget target, BuildingMetadataSourceKind source, UUID requestId,
		String reason) {
		return new FetchResult(repository.recordFailure(target, source, ComplexMetadataStatus.FAILED,
			ComplexMetadataFailureKind.TRANSIENT, reason, requestId, Instant.now().plusSeconds(86_400)), false, source);
	}

	private record FetchResult(BuildingMetadataAttemptResult result, boolean empty, BuildingMetadataSourceKind source) {}
}
