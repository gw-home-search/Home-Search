package com.home.application.ingest;

import java.time.Instant;
import java.util.List;

public interface ComplexMetadataEnrichmentRepository {

	List<ComplexMetadataLookup> findPending(int limit);

	void saveResolution(Long complexId, ComplexMetadataResolution resolution, Instant nextAttemptAt);
}
