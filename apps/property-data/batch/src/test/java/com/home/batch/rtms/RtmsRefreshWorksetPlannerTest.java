package com.home.batch.rtms;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.region.RegionSiGunGuCodeReader;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsRefreshWorksetPlannerTest {

    @Test
    @DisplayName("daily workset은 runDate 월과 lookback, configured lawdCds로 순차 unit을 만든다")
    void dailyWorksetUsesRunDateLookbackAndConfiguredLawdCds() {
        RtmsRefreshWorksetPlanner planner = new RtmsRefreshWorksetPlanner(RegionSiGunGuCodeReader.empty());

        List<RtmsRefreshWorkUnit> workset = planner.daily(LocalDate.parse("2026-07-07"), "11680,11710", 1);

        assertThat(workset)
                .containsExactly(
                        new RtmsRefreshWorkUnit("11680", "202607"),
                        new RtmsRefreshWorkUnit("11680", "202606"),
                        new RtmsRefreshWorkUnit("11710", "202607"),
                        new RtmsRefreshWorkUnit("11710", "202606"));
    }

    @Test
    @DisplayName("daily lawdCds 설정이 비어 있으면 reader의 시군구 코드를 사용한다")
    void dailyWorksetFallsBackToReaderCodes() {
        RtmsRefreshWorksetPlanner planner = new RtmsRefreshWorksetPlanner(() -> List.of("11110", "11140"));

        List<RtmsRefreshWorkUnit> workset = planner.daily(LocalDate.parse("2026-07-07"), "", 0);

        assertThat(workset)
                .containsExactly(
                        new RtmsRefreshWorkUnit("11110", "202607"), new RtmsRefreshWorkUnit("11140", "202607"));
    }

    @Test
    @DisplayName("backfill workset은 fromYmd부터 toYmd까지 inclusive 월 범위를 만든다")
    void backfillWorksetUsesInclusiveMonthRange() {
        RtmsRefreshWorksetPlanner planner = new RtmsRefreshWorksetPlanner(RegionSiGunGuCodeReader.empty());

        List<RtmsRefreshWorkUnit> workset = planner.backfill("202605", "202607", "11680");

        assertThat(workset)
                .containsExactly(
                        new RtmsRefreshWorkUnit("11680", "202605"),
                        new RtmsRefreshWorkUnit("11680", "202606"),
                        new RtmsRefreshWorkUnit("11680", "202607"));
    }
}
