package com.home.application.ingest;

import java.util.List;

public interface ComplexMetadataEnrichmentRepository {

	List<ComplexMetadataLookup> findPending(int limit);

	void saveResolution(Long complexId, ComplexMetadataResolution resolution);
}
