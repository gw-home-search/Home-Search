package com.home.application.ingest;

import java.util.Optional;

public interface TradeMatchEvidenceRepository {

	TradeMatchEvidenceRecord save(TradeMatchEvidenceCommand command);

	Optional<TradeMatchEvidenceRecord> findByRawIngestId(Long rawIngestId);

	static TradeMatchEvidenceRepository noop() {
		return new TradeMatchEvidenceRepository() {
			@Override
			public TradeMatchEvidenceRecord save(TradeMatchEvidenceCommand command) {
				return null;
			}

			@Override
			public Optional<TradeMatchEvidenceRecord> findByRawIngestId(Long rawIngestId) {
				return Optional.empty();
			}
		};
	}
}
