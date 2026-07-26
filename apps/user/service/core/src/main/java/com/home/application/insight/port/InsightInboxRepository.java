package com.home.application.insight.port;

import com.home.domain.user.insight.InsightInboxItem;
import java.util.List;

public interface InsightInboxRepository {
    InboxPage list(long userId, int page, int size);

    record InboxPage(List<InsightInboxItem> content, long totalElements) {
        public InboxPage {
            content = List.copyOf(content);
            if (totalElements < content.size()) {
                throw new IllegalArgumentException("totalElements cannot be smaller than content");
            }
        }
    }
}
