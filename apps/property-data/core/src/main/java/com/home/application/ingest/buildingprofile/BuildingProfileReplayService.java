package com.home.application.ingest.buildingprofile;

import com.home.domain.complex.buildingprofile.BuildingProfileParseStatus;
import com.home.domain.complex.buildingregister.BuildingRegisterRawPageStatus;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class BuildingProfileReplayService {
    private final BuildingProfileReplayRepository repository;
    private final BuildingProfilePageParser parser;

    public BuildingProfileReplayService(BuildingProfileReplayRepository repository, BuildingProfilePageParser parser) {
        this.repository = Objects.requireNonNull(repository);
        this.parser = Objects.requireNonNull(parser);
    }

    public BuildingProfileReplaySummary replay(BuildingProfileReplayCommand command) {
        repository.startOrResume(command);
        List<BuildingProfileRawPage> pages =
                repository.nextPages(command.parseRunId(), command.sourceCollectionId(), command.maxPages());
        long records = 0;
        int failures = 0;
        for (BuildingProfileRawPage page : pages) {
            if (page.rawStatus() == BuildingRegisterRawPageStatus.PROVIDER_FAILED || page.responseBody() == null) {
                repository.recordFailure(
                        command.parseRunId(),
                        page,
                        BuildingProfileParseStatus.PROVIDER_FAILED,
                        "STORED_PROVIDER_FAILURE");
                failures++;
                continue;
            }
            try {
                BuildingProfileParsedPage parsed =
                        parser.parse(page.endpoint(), page.pnu(), page.pageNo(), page.pageSize(), page.responseBody());
                if (!parsed.providerSuccessful()) {
                    repository.recordFailure(
                            command.parseRunId(),
                            page,
                            BuildingProfileParseStatus.PROVIDER_FAILED,
                            parsed.resultCode());
                    failures++;
                    continue;
                }
                repository.recordPage(command.parseRunId(), page, parsed);
                records += parsed.records().size();
            } catch (RuntimeException failure) {
                repository.recordFailure(
                        command.parseRunId(), page, BuildingProfileParseStatus.PARSE_FAILED, "PROFILE_PARSE_ERROR");
                failures++;
            }
        }
        boolean completed = repository.completeIfAllPagesProcessed(command.parseRunId(), command.sourceCollectionId());
        return new BuildingProfileReplaySummary(pages.size(), records, failures, completed);
    }
}
