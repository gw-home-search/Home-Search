package com.home.application.ingest;

import java.util.List;

public interface RawIngestReconciliationRepository {

	List<RawIngestReconciliationCandidate> findReceivedRowsLinkedToActiveTrade(int limit);
}
