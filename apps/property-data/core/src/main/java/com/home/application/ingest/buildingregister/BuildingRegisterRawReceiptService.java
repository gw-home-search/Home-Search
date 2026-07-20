package com.home.application.ingest.buildingregister;

import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BuildingRegisterRawReceiptService {
    private final BuildingRegisterRawPageRepository repository;

    public BuildingRegisterRawReceiptService(BuildingRegisterRawPageRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long receive(BuildingRegisterRawPageReceiptCommand command) {
        return repository.receive(command);
    }
}
