package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRegisterRawPageStatus;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BuildingRegisterRawPageFinalizer {
    private final BuildingRegisterRawPageRepository repository;

    public BuildingRegisterRawPageFinalizer(BuildingRegisterRawPageRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Transactional
    public void complete(
            long rawPageId, BuildingRegisterRawPageStatus status, List<BuildingRegisterRecordSnapshotCommand> records) {
        repository.complete(rawPageId, status, records);
    }
}
