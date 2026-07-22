package com.home.application.ingest.buildingprofile;

import com.home.domain.complex.buildingprofile.BuildingProfileParseStatus;
import java.util.List;
import java.util.UUID;

public interface BuildingProfileReplayRepository {
    void startOrResume(BuildingProfileReplayCommand command);

    List<BuildingProfileRawPage> nextPages(UUID parseRunId, UUID sourceCollectionId, int limit);

    void recordPage(UUID parseRunId, BuildingProfileRawPage rawPage, BuildingProfileParsedPage parsedPage);

    void recordFailure(
            UUID parseRunId, BuildingProfileRawPage rawPage, BuildingProfileParseStatus status, String failureCode);

    boolean completeIfAllPagesProcessed(UUID parseRunId, UUID sourceCollectionId);
}
