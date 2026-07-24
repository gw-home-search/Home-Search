package com.home.domain.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsSourceDateTest {

    @Test
    @DisplayName("RTMS 원천 날짜는 2000 기준 yy.MM.dd를 엄격히 파싱한다")
    void parsesStrictReducedYearDate() {
        assertThat(RtmsSourceDate.parse("26.07.23")).satisfies(date -> {
            assertThat(date.rawValue()).isEqualTo("26.07.23");
            assertThat(date.value()).isEqualTo(LocalDate.parse("2026-07-23"));
            assertThat(date.quality()).isEqualTo(RtmsSourceDateQuality.VALID);
        });
        assertThat(RtmsSourceDate.parse("00.01.01").value()).isEqualTo(LocalDate.parse("2000-01-01"));
        assertThat(RtmsSourceDate.parse("99.12.31").value()).isEqualTo(LocalDate.parse("2099-12-31"));
    }

    @Test
    @DisplayName("누락과 형식 오류는 예외 없이 서로 다른 품질 근거로 남는다")
    void distinguishesMissingAndInvalidWithoutThrowing() {
        assertThat(RtmsSourceDate.parse(" ").quality()).isEqualTo(RtmsSourceDateQuality.MISSING);
        assertThat(RtmsSourceDate.parse("26.02.30")).satisfies(date -> {
            assertThat(date.rawValue()).isEqualTo("26.02.30");
            assertThat(date.value()).isNull();
            assertThat(date.quality()).isEqualTo(RtmsSourceDateQuality.INVALID);
        });
        assertThat(RtmsSourceDate.parse("2026-07-23").quality()).isEqualTo(RtmsSourceDateQuality.INVALID);
        assertThat(RtmsSourceDate.parse("26.7.23").quality()).isEqualTo(RtmsSourceDateQuality.INVALID);
    }
}
