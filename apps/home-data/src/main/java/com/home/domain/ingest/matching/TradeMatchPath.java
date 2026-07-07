package com.home.domain.ingest.matching;

import java.util.Arrays;

/**
 * RTMS 거래가 complex에 연결된 근거 경로를 나타내며 match evidence의 저장값을 소유한다.
 */
public enum TradeMatchPath {

	APTSEQ("APTSEQ", "단지 일련번호", "RTMS aptSeq와 complex identity가 일치한 경로"),
	PNU_UNIQUE("PNU_UNIQUE", "PNU 단일 후보", "PNU에 연결된 complex 후보가 하나인 경로"),
	PNU_NAME("PNU_NAME", "PNU 이름 일치", "같은 PNU 후보 중 단지 이름으로 선택한 경로"),
	PNU_ALIAS_NAME("PNU_ALIAS_NAME", "PNU 별칭 일치", "같은 PNU 후보 중 보존된 이름 별칭으로 선택한 경로"),
	LEGACY_APT_SEQ("APT_SEQ", "레거시 단지 일련번호", "이전 evidence 또는 fixture에서 사용한 aptSeq 경로");

	private final String storedValue;
	private final String titleKo;
	private final String descriptionKo;

	TradeMatchPath(String storedValue, String titleKo, String descriptionKo) {
		this.storedValue = storedValue;
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public String storedValue() {
		return storedValue;
	}

	public String titleKo() {
		return titleKo;
	}

	public String descriptionKo() {
		return descriptionKo;
	}

	public static TradeMatchPath fromStoredValue(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("matchPath is required");
		}
		String normalized = value.trim();
		return Arrays.stream(values())
			.filter(path -> path.storedValue.equals(normalized))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("unsupported matchPath: " + normalized));
	}
}
