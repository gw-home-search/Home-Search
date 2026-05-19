package com.home.application.ingest;

import java.util.List;

public interface RawTradeIngestRepository {

	RawTradeIngestRecord save(RawTradeIngestRecord record);

	RawTradeIngestRecord updateStatus(Long id, RawTradeIngestStatus status, String failureReason);

	List<RawTradeIngestRecord> findByStatus(RawTradeIngestStatus status);
}
