package com.home.application.ingest.buildingregister;

import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BuildingRegisterRawReceiptService implements BuildingRegisterRawPageReceiver {
    private final BuildingRegisterRawPageRepository repository;

    public BuildingRegisterRawReceiptService(BuildingRegisterRawPageRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @Override
    public long receive(BuildingRegisterRawPageReceiptCommand command) {
        return repository.receive(command);
    }
}
