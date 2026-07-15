package com.home.admin.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AdminAuditService {
    private final JdbcClient jdbc;

    public AdminAuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AuditEvent> events(int limit, int offset) {
        if (limit < 1 || limit > 200 || offset < 0) throw new IllegalArgumentException("invalid audit page");
        return jdbc.sql("""
            SELECT id,actor_account_id,target_account_id,event_type,request_id,success,created_at
            FROM admin.admin_security_audit_event ORDER BY id DESC LIMIT :limit OFFSET :offset
            """)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, row) -> new AuditEvent(
                        rs.getLong("id"),
                        rs.getObject("actor_account_id", UUID.class),
                        rs.getObject("target_account_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("request_id"),
                        rs.getBoolean("success"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    public void recordBffRequest(UUID actorAccountId, String requestId, String eventType, boolean success) {
        if (actorAccountId == null
                || requestId == null
                || requestId.isBlank()
                || requestId.length() > 100
                || eventType == null
                || eventType.isBlank()
                || eventType.length() > 100) {
            throw new IllegalArgumentException("invalid BFF audit event");
        }
        jdbc.sql(
                        "INSERT INTO admin.admin_security_audit_event(actor_account_id,event_type,request_id,success) VALUES (:actor,:type,:requestId,:success)")
                .param("actor", actorAccountId)
                .param("type", eventType)
                .param("requestId", requestId)
                .param("success", success)
                .update();
    }

    public record AuditEvent(
            long id,
            UUID actorAccountId,
            UUID targetAccountId,
            String eventType,
            String requestId,
            boolean success,
            OffsetDateTime createdAt) {}
}
