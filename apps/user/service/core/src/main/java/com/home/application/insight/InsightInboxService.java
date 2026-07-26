package com.home.application.insight;

import com.home.application.favorite.InvalidPaginationException;
import com.home.application.insight.port.InsightInboxRepository;
import com.home.application.insight.port.InsightInboxRepository.InboxPage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InsightInboxService {

    private final InsightInboxRepository repository;

    public InsightInboxService(InsightInboxRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public InboxPage list(long userId, int page, int size) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        if (page < 0 || size < 1 || size > 100) throw new InvalidPaginationException();
        return repository.list(userId, page, size);
    }
}
