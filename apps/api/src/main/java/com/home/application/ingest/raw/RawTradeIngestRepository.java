package com.home.application.ingest.raw;

import java.util.List;

import com.home.domain.ingest.raw.RawTradeIngestStatus;

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
	 * 같은 source/source_key/payload_hash가 이미 처리된 raw evidence로 존재하는지 확인합니다.
	 *
	 * @param rawIngestId 현재 raw ingest id. 이 id보다 앞선 row만 중복 후보로 봅니다.
	 * @param source 외부 원천
	 * @param sourceKey 원천 identity
	 * @param payloadHash 원천 payload hash
	 * @return 현재 row보다 먼저 처리된 같은 source/source_key/payload_hash raw evidence 존재 여부
	 */
	boolean existsProcessedBySourceAndSourceKeyAndPayloadHashBefore(
		Long rawIngestId,
		String source,
		String sourceKey,
		String payloadHash
	);

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
	 * failed match rematch처럼 대량 backlog를 배치로 소비하는 경로에서 DB 조회 단계부터 제한합니다.
	 *
	 * @param status 조회할 raw ingest status
	 * @param limit 최대 조회 건수
	 * @return status가 일치하는 raw ingest records. limit이 0 이하면 빈 목록
	 */
	default List<RawTradeIngestRecord> findByStatus(RawTradeIngestStatus status, int limit) {
		if (limit <= 0) {
			return List.of();
		}
		return findByStatus(status).stream()
			.limit(limit)
			.toList();
	}

	/**
	 * 운영자가 raw ingest 실패/중복 evidence를 read-only summary로 조회합니다.
	 * Raw payload와 source_key 전문은 반환하지 않습니다.
	 *
	 * @param query source, lawdCd, dealYmd 범위, status 조건
	 * @return status/source/lawdCd/dealYmd/failureReason별 count summary
	 */
	List<RawTradeIngestFailureSummary> summarizeFailures(RawTradeIngestFailureQuery query);
}
