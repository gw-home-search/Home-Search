package com.home.application.ingest.buildingprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LegalDongCodeImportServiceTest {
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 7, 1);

    @Test
    @DisplayName("검증된 법정동 코드 변경 매핑을 가져온다")
    void importsValidatedVersionedMappings() {
        CapturingRepository repository = new CapturingRepository();
        var command = command(List.of(mapping("2811010100", "2811010200")));

        assertThat(new LegalDongCodeImportService(repository).importMappings(command))
                .isOne();
        assertThat(repository.command).isEqualTo(command);
    }

    @Test
    @DisplayName("중복 구코드와 다른 시행일을 거부한다")
    void rejectsDuplicateOldCodeAndMismatchedEffectiveDate() {
        var service =
                new LegalDongCodeImportService(command -> command.mappings().size());
        assertThatThrownBy(() -> service.importMappings(
                        command(List.of(mapping("2811010100", "2811010200"), mapping("2811010100", "2811010300")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> service.importMappings(
                        command(List.of(new LegalDongCodeMapping("2811010100", "2811010200", EFFECTIVE.plusDays(1))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveDate");
    }

    private LegalDongCodeImportCommand command(List<LegalDongCodeMapping> mappings) {
        return new LegalDongCodeImportCommand(UUID.randomUUID(), EFFECTIVE, "a".repeat(64), "official.csv", mappings);
    }

    private LegalDongCodeMapping mapping(String oldCode, String newCode) {
        return new LegalDongCodeMapping(oldCode, newCode, EFFECTIVE);
    }

    private static final class CapturingRepository implements LegalDongCodeImportRepository {
        LegalDongCodeImportCommand command;

        public int importMappings(LegalDongCodeImportCommand value) {
            command = value;
            return value.mappings().size();
        }
    }
}
