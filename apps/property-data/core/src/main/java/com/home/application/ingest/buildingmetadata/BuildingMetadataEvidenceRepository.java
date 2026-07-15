package com.home.application.ingest.buildingmetadata;

import com.home.domain.complex.buildingmetadata.BuildingMetadataSourceKind;
import com.home.domain.complex.metadata.ComplexMetadataFailureKind;
import com.home.domain.complex.metadata.ComplexMetadataStatus;
import java.util.List;
import java.util.UUID;

public interface BuildingMetadataEvidenceRepository {
    List<BuildingMetadataTarget> findTargets(
            String mode, int limit, Long fromComplexId, Long toComplexId, UUID requestId);

    BuildingMetadataAttemptResult recordAmbiguousPnu(BuildingMetadataTarget target, UUID requestId);

    BuildingMetadataAttemptResult apply(
            BuildingMetadataTarget target,
            BuildingMetadataSourceKind source,
            ParsedBuildingMetadataSource parsed,
            UUID requestId);

    BuildingMetadataAttemptResult recordFailure(
            BuildingMetadataTarget target,
            BuildingMetadataSourceKind source,
            ComplexMetadataStatus status,
            ComplexMetadataFailureKind failureKind,
            String reason,
            UUID requestId,
            java.time.Instant nextAttemptAt);
}
