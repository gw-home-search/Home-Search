package com.home.application.ingest.metadata;

public record OdcloudPnuPrefixAlias(Long id, String canonicalPrefix, String sourcePrefix) {

	public String translate(String canonicalPnu) {
		if (canonicalPnu == null || canonicalPnu.length() != 19 || !canonicalPnu.startsWith(canonicalPrefix)) {
			return null;
		}
		return sourcePrefix + canonicalPnu.substring(8);
	}
}
