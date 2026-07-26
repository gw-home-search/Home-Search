package com.home.application.news.selection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MajorNewsComplexSelectionServiceTest {

    @Test
    @DisplayName("17개 시도 최소 5개와 largest remainder 배분으로 정확히 200개를 결정한다")
    void allocatesExactlyTwoHundredAcrossAllSido() {
        FakeRepository repository = new FakeRepository();
        for (int sido = 1; sido <= 17; sido++) {
            for (int rank = 1; rank <= 60; rank++) {
                repository.candidates.add(new MajorNewsComplexCandidate(
                        sido * 1000L + rank,
                        String.format("%02d", sido),
                        "시도" + sido,
                        "시군구" + sido,
                        "법정동" + sido,
                        "단지" + rank,
                        (18 - sido) * 1000 - rank,
                        1000 - rank));
            }
        }

        List<MajorNewsComplexCandidate> selected =
                new MajorNewsComplexSelectionService(repository).select(LocalDate.parse("2026-07-24"));

        assertThat(selected).hasSize(200);
        assertThat(selected.stream().map(MajorNewsComplexCandidate::sidoCode).distinct())
                .hasSize(17);
        for (int sido = 1; sido <= 17; sido++) {
            String code = String.format("%02d", sido);
            assertThat(selected.stream()
                            .filter(candidate -> candidate.sidoCode().equals(code)))
                    .hasSizeGreaterThanOrEqualTo(5);
        }
        assertThat(repository.publishedWeek).isEqualTo(LocalDate.parse("2026-07-20"));
    }

    private static final class FakeRepository implements MajorNewsComplexSelectionRepository {
        private final List<MajorNewsComplexCandidate> candidates = new ArrayList<>();
        private LocalDate publishedWeek;

        @Override
        public boolean hasPublishedSelection(LocalDate selectionWeek) {
            return false;
        }

        @Override
        public List<MajorNewsComplexCandidate> findCandidates(LocalDate asOfDate) {
            return List.copyOf(candidates);
        }

        @Override
        public void publish(LocalDate selectionWeek, List<MajorNewsComplexCandidate> selected) {
            publishedWeek = selectionWeek;
        }
    }
}
