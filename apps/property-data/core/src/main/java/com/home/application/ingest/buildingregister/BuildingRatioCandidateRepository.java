package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRatioEvaluation;
import java.util.Map;

public interface BuildingRatioCandidateRepository {
    BuildingRatioRecordedEvaluation record(
            long matchId, BuildingRatioEvaluation evaluation, Map<String, Long> recordIdsByManagementKey);
}
