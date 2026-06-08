package com.home.application.ingest.reconciliation;

import java.util.List;

public interface RawIngestReconciliationRepository {

	List<RawIngestReconciliationCandidate> findReceivedRowsLinkedToActiveTrade(int limit);
}
