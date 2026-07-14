package com.home.application.ingest.raw;

import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RawReceiptService {

    private final RawTradeIngestRepository repository;

    public RawReceiptService(RawTradeIngestRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public RawTradeIngestRecord receive(RawTradeIngestRecord record) {
        return repository.save(Objects.requireNonNull(record, "record is required"));
    }
}
