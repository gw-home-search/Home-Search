package com.home.batch.metadata;

import com.home.application.ingest.buildingprofile.LegalDongCodeImportCommand;
import com.home.application.ingest.buildingprofile.LegalDongCodeImportService;
import com.home.application.ingest.buildingprofile.LegalDongCodeMapping;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

class LegalDongCodeImportTasklet implements Tasklet {
    private static final long MAX_SOURCE_BYTES = 10L * 1024 * 1024;
    private static final String APPROVED_HEADER = "old_legal_dong_code,new_legal_dong_code,effective_date";
    private final LegalDongCodeImportService service;
    private final BuildingMetadataExecutionLock executionLock;

    LegalDongCodeImportTasklet(LegalDongCodeImportService service, BuildingMetadataExecutionLock executionLock) {
        this.service = service;
        this.executionLock = executionLock;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Object> params = chunkContext.getStepContext().getJobParameters();
        Path source = Path.of(required(params, "sourceFile")).normalize();
        if (!source.isAbsolute()) throw new IllegalArgumentException("sourceFile must be absolute");
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new IllegalArgumentException("sourceFile must be a regular non-symlink file");
        }
        try {
            long size = Files.size(source);
            if (size <= 0 || size > MAX_SOURCE_BYTES) throw new IllegalArgumentException("sourceFile size is invalid");
            byte[] bytes = Files.readAllBytes(source);
            List<String> lines =
                    new String(bytes, StandardCharsets.UTF_8).lines().toList();
            if (lines.isEmpty() || !APPROVED_HEADER.equals(lines.getFirst().replace("\ufeff", ""))) {
                throw new IllegalArgumentException("sourceFile header is not approved");
            }
            LocalDate effectiveDate = LocalDate.parse(required(params, "effectiveDate"));
            List<LegalDongCodeMapping> mappings = new ArrayList<>();
            for (int index = 1; index < lines.size(); index++) {
                if (lines.get(index).isBlank()) continue;
                String[] columns = lines.get(index).split(",", -1);
                if (columns.length != 3) throw new IllegalArgumentException("sourceFile row has invalid column count");
                mappings.add(new LegalDongCodeMapping(
                        columns[0].trim(), columns[1].trim(), LocalDate.parse(columns[2].trim())));
            }
            LegalDongCodeImportCommand command = new LegalDongCodeImportCommand(
                    UUID.fromString(required(params, "importId")),
                    effectiveDate,
                    sha256(bytes),
                    source.getFileName().toString(),
                    mappings);
            try (BuildingMetadataExecutionLock.Lock ignored = executionLock.acquire()) {
                service.importMappings(command);
            }
            return RepeatStatus.FINISHED;
        } catch (IOException exception) {
            throw new IllegalStateException("legal dong code source read failed", exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            StringBuilder value = new StringBuilder(64);
            for (byte current : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                value.append(String.format("%02x", current));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String required(Map<String, Object> params, String name) {
        Object value = params.get(name);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.toString();
    }
}
