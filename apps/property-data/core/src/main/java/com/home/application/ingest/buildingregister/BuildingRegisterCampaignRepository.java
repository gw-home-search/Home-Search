package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRegisterComplexMatch;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface BuildingRegisterCampaignRepository {
    List<BuildingRegisterCampaignTarget> freezeOrLoad(BuildingRegisterCampaignCommand command);

    long recordMatch(UUID collectionId, String pnu, int pnuComplexCount, BuildingRegisterComplexMatch match);

    Map<String, Long> sourceRecordIds(UUID collectionId, String pnu);

    boolean completeIfAllTargetsMatched(UUID collectionId);
}
