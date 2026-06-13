package com.home.application.ingest.metadata;

import java.util.Optional;

public interface OdcloudPnuPrefixAliasLookup {

	Optional<OdcloudPnuPrefixAlias> findApprovedByCanonicalPnu(String canonicalPnu);

	static OdcloudPnuPrefixAliasLookup empty() {
		return ignored -> Optional.empty();
	}
}
