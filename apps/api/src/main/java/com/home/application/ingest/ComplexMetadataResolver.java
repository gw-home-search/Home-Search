package com.home.application.ingest;

import java.util.Optional;

@FunctionalInterface
public interface ComplexMetadataResolver {

	Optional<ComplexMetadata> resolve(OpenApiTradeItem item, String pnu, String parcelAddress);

	static ComplexMetadataResolver noop() {
		return (item, pnu, parcelAddress) -> Optional.empty();
	}
}
