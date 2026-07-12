package com.home.application.ingest.metadata;

import java.util.List;
import java.util.UUID;

public interface OdcMetadataGapFillRepository {
	List<OdcMetadataGapFillTarget> findTargets(int limit, Long fromComplexId, long toComplexId, UUID requestId);

	OdcMetadataGapFillOutcome recordAmbiguous(OdcMetadataGapFillTarget target, UUID requestId);

	OdcMetadataGapFillOutcome saveResolution(OdcMetadataGapFillTarget target, ComplexMetadataResolution resolution,
		UUID requestId);
}
