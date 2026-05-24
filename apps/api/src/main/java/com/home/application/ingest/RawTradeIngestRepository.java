package com.home.application.ingest;

import java.util.List;

/**
 * raw trade ingest evidence를 normalized insert보다 먼저 저장하고 status별로 조회하는 application port입니다.
 */
public interface RawTradeIngestRepository {

	/**
	 * 원천 payload를 normalized trade 처리보다 먼저 저장합니다.
	 *
	 * @param record raw ingest evidence
	 * @return DB id와 timestamp가 반영된 raw ingest record
	 */
	RawTradeIngestRecord save(RawTradeIngestRecord record);

	/**
	 * raw ingest row의 처리 결과와 실패 사유를 갱신합니다.
	 *
	 * @param id raw ingest id
	 * @param status 처리 결과 status
	 * @param failureReason 실패 또는 duplicate 사유
	 * @return 갱신된 raw ingest record
	 */
	RawTradeIngestRecord updateStatus(Long id, RawTradeIngestStatus status, String failureReason);

	/**
	 * failed match처럼 운영자가 설명해야 하는 raw evidence를 status 기준으로 조회합니다.
	 *
	 * @param status 조회할 raw ingest status
	 * @return status가 일치하는 raw ingest records
	 */
	List<RawTradeIngestRecord> findByStatus(RawTradeIngestStatus status);

	/**
	 * 운영자가 raw ingest 실패/중복 evidence를 read-only summary로 조회합니다.
	 * Raw payload와 source_key 전문은 반환하지 않습니다.
	 *
	 * @param query source, lawdCd, dealYmd 범위, status 조건
	 * @return status/source/lawdCd/dealYmd/failureReason별 count summary
	 */
	List<RawTradeIngestFailureSummary> summarizeFailures(RawTradeIngestFailureQuery query);
}
