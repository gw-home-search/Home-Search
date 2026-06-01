package com.home.application.ingest;

import java.util.Objects;

public class RawIngestReconciliationService {

	private final RawIngestReconciliationRepository reconciliationRepository;
	private final RawTradeIngestRepository rawTradeIngestRepository;

	public RawIngestReconciliationService(
		RawIngestReconciliationRepository reconciliationRepository,
		RawTradeIngestRepository rawTradeIngestRepository
	) {
		this.reconciliationRepository = Objects.requireNonNull(reconciliationRepository);
		this.rawTradeIngestRepository = Objects.requireNonNull(rawTradeIngestRepository);
	}

	public RawIngestReconciliationResult reconcileReceived(int limit) {
		if (limit <= 0) {
			return RawIngestReconciliationResult.empty();
		}
		RawIngestReconciliationResult result = RawIngestReconciliationResult.empty();
		for (RawIngestReconciliationCandidate candidate
			: reconciliationRepository.findReceivedRowsLinkedToActiveTrade(limit)) {
			rawTradeIngestRepository.updateStatus(candidate.rawIngestId(), RawTradeIngestStatus.NORMALIZED, null);
			result = result.plusNormalized();
		}
		return result;
	}
}
