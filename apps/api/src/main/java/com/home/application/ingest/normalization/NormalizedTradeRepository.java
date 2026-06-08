package com.home.application.ingest.normalization;

/**
 * source_key와 fallback trade identity를 기준으로 normalized trade를 duplicate-safe하게 저장하는 application port입니다.
 */
public interface NormalizedTradeRepository {

	/**
	 * source와 source_key가 이미 normalized 처리 registry에 있는지 확인합니다.
	 *
	 * @param source 원천 source 이름
	 * @param sourceKey 원천 trade identity
	 * @return 이미 처리되었거나 duplicate로 등록된 source_key이면 true
	 */
	boolean existsBySourceAndSourceKey(String source, String sourceKey);

	/**
	 * source_key 해제 row를 처리하고, 연결된 normalized trade가 있으면 public 조회에서 제외합니다.
	 * 아직 normalized row가 없더라도 registry를 선점해 이후 정상 row insert를 막습니다.
	 *
	 * @param source 원천 source 이름
	 * @param sourceKey 원천 trade identity
	 * @param rawIngestId 해제 raw ingest id
	 * @return 활성 normalized trade가 이번 호출로 제외되면 true
	 */
	boolean cancelBySourceAndSourceKey(String source, String sourceKey, Long rawIngestId);

	/**
	 * source_key registry와 fallback identity를 모두 통과한 경우에만 normalized trade를 저장합니다.
	 *
	 * @param command normalized trade insert command
	 * @return 새 normalized trade가 생성되면 true, duplicate이면 false
	 */
	boolean insertIfAbsent(NormalizedTradeCommand command);
}
