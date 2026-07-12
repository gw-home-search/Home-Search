package com.home.sourcedata;

/**
 * Coordinate source와 VWorld WFS raw/cache 저장 runtime의 소유권 경계입니다.
 */
public final class SourceDataBoundary {

	public static final String APP_NAME = "home-search-source-data";
	public static final String COORDINATE_SOURCE_MIGRATION = "db/migration/coordinate-source";
	public static final String GEO_ENRICHMENT_MIGRATION = COORDINATE_SOURCE_MIGRATION;

	private SourceDataBoundary() {
	}
}
