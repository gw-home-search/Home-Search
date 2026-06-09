package com.home.domain.coordinate;

import com.home.domain.coordinate.ComplexCoordinateCaseStatus;

/**
 * 외부 단지 식별 검증 결과를 나타낸다. 표시 좌표 확정 전에 단지 정체성이 안전한지 판단하는 내부 상태다.
 */
public enum ComplexCoordinateIdentityVerificationStatus {

	CONFIRMED("확인", "대상 단지와 외부 후보가 안전하게 일치하는 상태"),
	AMBIGUOUS("모호", "복수 후보 또는 상충 근거로 단지 정체성을 확정할 수 없는 상태"),
	UNAVAILABLE("불가", "외부 검증에 필요한 원천 데이터를 확보할 수 없는 상태"),
	FAILED("실패", "외부 검증 호출 또는 처리 중 실패가 발생한 상태");

	private final String titleKo;
	private final String descriptionKo;

	ComplexCoordinateIdentityVerificationStatus(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	/**
	 * 한국어 짧은 제목을 반환한다.
	 */
	public String titleKo() {
		return titleKo;
	}

	/**
	 * 한국어 설명을 반환한다.
	 */
	public String descriptionKo() {
		return descriptionKo;
	}

	/**
	 * 현재 검증 결과가 표시 좌표 확정을 막아야 하는지 판단한다.
	 */
	public boolean shouldBlock(boolean blockOnUnavailableIdentity, boolean blockOnFailedIdentity) {
		return switch (this) {
			case CONFIRMED -> false;
			case AMBIGUOUS -> true;
			case UNAVAILABLE -> blockOnUnavailableIdentity;
			case FAILED -> blockOnFailedIdentity;
		};
	}

	/**
	 * 차단된 검증 결과를 좌표 예외 상태로 변환한다.
	 */
	public ComplexCoordinateCaseStatus toBlockedCaseStatus() {
		return switch (this) {
			case AMBIGUOUS -> ComplexCoordinateCaseStatus.AMBIGUOUS;
			case UNAVAILABLE -> ComplexCoordinateCaseStatus.UNAVAILABLE;
			case FAILED -> ComplexCoordinateCaseStatus.FAILED;
			case CONFIRMED -> throw new IllegalStateException("confirmed identity must not be blocked");
		};
	}
}
