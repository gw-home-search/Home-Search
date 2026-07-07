package com.home.application.ingest.metadata.admin;

import java.util.List;

import com.home.application.ingest.metadata.admin.MetadataAdminModels.ActionResult;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Alias;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Detail;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Pending;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Summary;

public interface MetadataAdminRepository {
	List<Pending> findPending(int limit, int offset);
	Summary summary();
	Detail detail(long complexId);
	ActionResult retry(long complexId, String actor, String reason);
	ActionResult hold(long complexId, String actor, String reason);
	List<Alias> aliases();
	Alias proposeAlias(String canonicalPrefix, String sourcePrefix, String actor, String reason);
	ActionResult approveAlias(long aliasId, String actor, String reason);
	ActionResult disableAlias(long aliasId, String actor, String reason);
}
