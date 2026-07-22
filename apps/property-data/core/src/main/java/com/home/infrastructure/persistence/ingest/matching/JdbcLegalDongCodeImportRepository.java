package com.home.infrastructure.persistence.ingest.matching;

import com.home.application.ingest.buildingprofile.LegalDongCodeImportCommand;
import com.home.application.ingest.buildingprofile.LegalDongCodeImportRepository;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcLegalDongCodeImportRepository implements LegalDongCodeImportRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcLegalDongCodeImportRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
    }

    @Override
    public int importMappings(LegalDongCodeImportCommand command) {
        return transaction.execute(ignored -> {
            jdbc.sql("""
                        INSERT INTO legal_dong_code_import
                          (import_id,effective_date,source_sha256,source_name,status)
                        VALUES (:id,:effective,:hash,:name,'IMPORTING')
                        ON CONFLICT (import_id) DO NOTHING
                        """)
                    .param("id", command.importId())
                    .param("effective", command.effectiveDate())
                    .param("hash", command.sourceSha256())
                    .param("name", command.sourceName())
                    .update();
            ImportIdentity stored = jdbc.sql("""
                        SELECT effective_date,source_sha256,source_name,status
                        FROM legal_dong_code_import WHERE import_id=:id FOR UPDATE
                        """)
                    .param("id", command.importId())
                    .query((rs, rowNum) -> new ImportIdentity(
                            rs.getObject("effective_date", java.time.LocalDate.class),
                            rs.getString("source_sha256"),
                            rs.getString("source_name"),
                            rs.getString("status")))
                    .single();
            if (!stored.effectiveDate().equals(command.effectiveDate())
                    || !stored.sourceSha256().equals(command.sourceSha256())
                    || !stored.sourceName().equals(command.sourceName())) {
                throw new IllegalArgumentException("importId is already frozen with different source inputs");
            }
            if ("COMPLETED".equals(stored.status())) return command.mappings().size();
            command.mappings()
                    .forEach(mapping -> jdbc.sql("""
                        INSERT INTO legal_dong_code_mapping
                          (import_id,old_legal_dong_code,new_legal_dong_code,effective_date)
                        VALUES (:id,:old_code,:new_code,:effective)
                        ON CONFLICT (import_id,old_legal_dong_code) DO NOTHING
                        """)
                            .param("id", command.importId())
                            .param("old_code", mapping.oldLegalDongCode())
                            .param("new_code", mapping.newLegalDongCode())
                            .param("effective", mapping.effectiveDate())
                            .update());
            int storedCount = jdbc.sql("SELECT count(*) FROM legal_dong_code_mapping WHERE import_id=:id")
                    .param("id", command.importId())
                    .query(Integer.class)
                    .single();
            if (storedCount != command.mappings().size()) {
                throw new IllegalStateException("stored legal dong mapping count does not match source");
            }
            jdbc.sql("""
                        UPDATE legal_dong_code_import
                        SET status='COMPLETED',row_count=:count,completed_at=COALESCE(completed_at,now())
                        WHERE import_id=:id AND status='IMPORTING'
                        """)
                    .param("count", storedCount)
                    .param("id", command.importId())
                    .update();
            return storedCount;
        });
    }

    private record ImportIdentity(
            java.time.LocalDate effectiveDate, String sourceSha256, String sourceName, String status) {}
}
