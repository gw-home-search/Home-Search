package com.home.application.ingest.buildingregister;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.domain.complex.buildingregister.BuildingRegisterCollectionStrategy;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import com.home.domain.complex.buildingregister.BuildingRegisterRawPageStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingRegisterCollectionServiceTest {
    private static final String PNU = "1168010300101400001";
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174120");
    private static final UUID REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174121");

    @Test
    @DisplayName("건축물대장 적응형 수집 처리를 검증한다")
    void adaptiveCollectionStopsAfterValidSingleRecap() {
        Fixture fixture = new Fixture();
        fixture.client.respond(BuildingRegisterEndpoint.RECAP_TITLE, page(recap("20", "80"), 1));

        BuildingRegisterCollectionResult result = fixture.service.collect(command(10));

        assertThat(fixture.client.calls).containsExactly("RECAP_TITLE:1:100");
        assertThat(result.requestCount()).isOne();
        assertThat(result.recapRecords()).hasSize(1);
        assertThat(result.titleRecords()).isEmpty();
        assertThat(fixture.store.observedTotalCounts).containsExactly(1);
    }

    @Test
    @DisplayName("건축물대장 적응형 수집 처리를 검증한다")
    void adaptiveCollectionFetchesTitleAndBasicOverviewWhenOneRecapRatioIsZero() {
        Fixture fixture = new Fixture();
        fixture.client.respond(BuildingRegisterEndpoint.RECAP_TITLE, page(recap("20", "0"), 1));
        fixture.client.respond(BuildingRegisterEndpoint.TITLE, page(title(), 1));
        fixture.client.respond(BuildingRegisterEndpoint.BASIC_OVERVIEW, page(basic(), 1));

        BuildingRegisterCollectionResult result = fixture.service.collect(command(10));

        assertThat(fixture.client.calls).containsExactly("RECAP_TITLE:1:100", "TITLE:1:100", "BASIC_OVERVIEW:1:100");
        assertThat(result.fallbackFields()).extracting(Enum::name).containsExactly("FLOOR_AREA_RATIO");
    }

    @Test
    @DisplayName("표제부에 fallback 후보 구성요소가 없으면 기본개요를 호출하지 않는다")
    void adaptiveCollectionSkipsBasicOverviewWhenTitleCannotProduceFallbackCandidate() {
        Fixture fixture = new Fixture();
        fixture.client.respond(BuildingRegisterEndpoint.RECAP_TITLE, page(recap("20", "0"), 1));
        fixture.client.respond(BuildingRegisterEndpoint.TITLE, page(titleWithoutRatioCandidate(), 1));
        fixture.client.respond(BuildingRegisterEndpoint.BASIC_OVERVIEW, page(basic(), 1));

        BuildingRegisterCollectionResult result = fixture.service.collect(command(10));

        assertThat(fixture.client.calls).containsExactly("RECAP_TITLE:1:100", "TITLE:1:100");
        assertThat(result.basicOverviewRecords()).isEmpty();
        assertThat(result.fallbackFields()).extracting(Enum::name).containsExactly("FLOOR_AREA_RATIO");
    }

    @Test
    @DisplayName("건축물대장 적응형 수집 처리를 검증한다")
    void adaptiveCollectionFetchesBasicOverviewWhenEmptyRecapHasMultipleTitles() {
        Fixture fixture = new Fixture();
        fixture.client.respond(
                BuildingRegisterEndpoint.RECAP_TITLE,
                new ParsedBuildingRegisterPage("00", "NORMAL SERVICE", 0, List.of()));
        fixture.client.respond(
                BuildingRegisterEndpoint.TITLE,
                new ParsedBuildingRegisterPage("00", "NORMAL SERVICE", 2, List.of(title(), title())));
        fixture.client.respond(BuildingRegisterEndpoint.BASIC_OVERVIEW, page(basic(), 1));

        fixture.service.collect(command(10));

        assertThat(fixture.client.calls).containsExactly("RECAP_TITLE:1:100", "TITLE:1:100", "BASIC_OVERVIEW:1:100");
    }

    @Test
    @DisplayName("건축물대장 적응형 수집 처리를 검증한다")
    void providerFailureIsNotTreatedAsEmptyAndNeverTriggersFallback() {
        Fixture fixture = new Fixture();
        fixture.client.respond(
                BuildingRegisterEndpoint.RECAP_TITLE,
                new BuildingRegisterPageResponse(
                        BuildingRegisterEndpoint.RECAP_TITLE,
                        PNU,
                        1,
                        100,
                        500,
                        "provider failure",
                        16,
                        "a".repeat(64),
                        false));

        BuildingRegisterCollectionResult result = fixture.service.collect(command(10));

        assertThat(result.status()).isEqualTo(BuildingRegisterCollectionStatus.PROVIDER_FAILED);
        assertThat(fixture.client.calls).containsExactly("RECAP_TITLE:1:100");
        assertThat(fixture.finalizations).containsExactly(BuildingRegisterRawPageStatus.PROVIDER_FAILED);
    }

    @Test
    @DisplayName("전송 timeout은 raw 실패 증거로 남기고 fallback을 호출하지 않는다")
    void transportTimeoutIsRecordedAndNeverTriggersFallback() {
        Fixture fixture = new Fixture();
        fixture.client.fail("TRANSPORT_TIMEOUT");

        BuildingRegisterCollectionResult result = fixture.service.collect(command(10));

        assertThat(result.status()).isEqualTo(BuildingRegisterCollectionStatus.PROVIDER_FAILED);
        assertThat(result.requestCount()).isOne();
        assertThat(fixture.client.calls).containsExactly("RECAP_TITLE:1:100");
        assertThat(fixture.finalizations).containsExactly(BuildingRegisterRawPageStatus.PROVIDER_FAILED);
        assertThat(fixture.receipts).singleElement().satisfies(receipt -> {
            assertThat(receipt.httpStatus()).isNull();
            assertThat(receipt.responseBody()).isNull();
            assertThat(receipt.byteCount()).isZero();
            assertThat(receipt.providerStatus()).isEqualTo("TRANSPORT_TIMEOUT");
        });
    }

    @Test
    @DisplayName("건축물대장 적응형 수집 처리를 검증한다")
    void authenticationOrQuotaHttpFailureStopsTheCollectionRun() {
        Fixture fixture = new Fixture();
        fixture.client.respond(
                BuildingRegisterEndpoint.RECAP_TITLE,
                new BuildingRegisterPageResponse(
                        BuildingRegisterEndpoint.RECAP_TITLE,
                        PNU,
                        1,
                        100,
                        429,
                        "quota exceeded",
                        14,
                        "a".repeat(64),
                        false));

        assertThatThrownBy(() -> fixture.service.collect(command(10)))
                .isInstanceOf(BuildingRegisterFatalProviderException.class)
                .hasMessageContaining("429");
        assertThat(fixture.client.calls).containsExactly("RECAP_TITLE:1:100");
        assertThat(fixture.finalizations).containsExactly(BuildingRegisterRawPageStatus.PROVIDER_FAILED);
    }

    @Test
    @DisplayName("건축물대장 적응형 수집 처리를 검증한다")
    void authenticationOrQuotaProviderCodeStopsTheCollectionRun() {
        Fixture fixture = new Fixture();
        fixture.client.respond(
                BuildingRegisterEndpoint.RECAP_TITLE,
                new ParsedBuildingRegisterPage("22", "quota exceeded", 0, List.of()));

        assertThatThrownBy(() -> fixture.service.collect(command(10)))
                .isInstanceOf(BuildingRegisterFatalProviderException.class)
                .hasMessageContaining("22");
        assertThat(fixture.client.calls).containsExactly("RECAP_TITLE:1:100");
        assertThat(fixture.finalizations).containsExactly(BuildingRegisterRawPageStatus.PROVIDER_FAILED);
    }

    @Test
    @DisplayName("건축물대장 적응형 수집 처리를 검증한다")
    void actualRequestsConsumeBudgetBeforeFallbackCall() {
        Fixture fixture = new Fixture();
        fixture.client.respond(BuildingRegisterEndpoint.RECAP_TITLE, page(recap("20", "0"), 1));

        assertThatThrownBy(() -> fixture.service.collect(command(1)))
                .isInstanceOf(BuildingRegisterRequestBudgetExceededException.class);
        assertThat(fixture.client.calls).containsExactly("RECAP_TITLE:1:100");
    }

    @Test
    @DisplayName("건축물대장 적응형 수집 처리를 검증한다")
    void oversizedResponseRestartsEndpointSnapshotWithSmallerPageSize() {
        Fixture fixture = new Fixture();
        fixture.client.respond(
                BuildingRegisterEndpoint.RECAP_TITLE,
                new BuildingRegisterPageResponse(
                        BuildingRegisterEndpoint.RECAP_TITLE, PNU, 1, 100, 200, null, 2_097_153, "b".repeat(64), true));
        fixture.client.respond(BuildingRegisterEndpoint.RECAP_TITLE, page(recap("20", "80"), 1));

        BuildingRegisterCollectionResult result = fixture.service.collect(command(10));

        assertThat(fixture.client.calls).containsExactly("RECAP_TITLE:1:100", "RECAP_TITLE:1:50");
        assertThat(fixture.store.abandonedPageSizes).containsExactly(100);
        assertThat(result.status()).isEqualTo(BuildingRegisterCollectionStatus.COLLECTED);
    }

    @Test
    @DisplayName("건축물대장 적응형 수집 처리를 검증한다")
    void completedPageIsReusedWithoutExternalRequest() {
        Fixture fixture = new Fixture();
        fixture.store.completed.put(
                "RECAP_TITLE:100:1", new BuildingRegisterCompletedPage(1, List.of(recap("20", "80"))));

        BuildingRegisterCollectionResult result = fixture.service.collect(command(10));

        assertThat(fixture.client.calls).isEmpty();
        assertThat(result.requestCount()).isZero();
        assertThat(result.recapRecords()).hasSize(1);
    }

    @Test
    @DisplayName("건축물대장 적응형 수집 처리를 검증한다")
    void paginatesUntilEveryReportedRecordIsCollected() {
        Fixture fixture = new Fixture();
        fixture.client.respond(BuildingRegisterEndpoint.RECAP_TITLE, page(recap("20", "80"), 101));
        fixture.client.respond(BuildingRegisterEndpoint.RECAP_TITLE, page(recap("20", "80"), 101));
        fixture.client.respond(
                BuildingRegisterEndpoint.TITLE, new ParsedBuildingRegisterPage("00", "NORMAL SERVICE", 0, List.of()));
        fixture.client.respond(
                BuildingRegisterEndpoint.BASIC_OVERVIEW,
                new ParsedBuildingRegisterPage("00", "NORMAL SERVICE", 0, List.of()));

        BuildingRegisterCollectionResult result = fixture.service.collect(command(10));

        assertThat(fixture.client.calls)
                .containsExactly("RECAP_TITLE:1:100", "RECAP_TITLE:2:100", "TITLE:1:100", "BASIC_OVERVIEW:1:100");
        assertThat(result.recapRecords()).hasSize(2);
        assertThat(fixture.finalizations)
                .containsExactly(
                        BuildingRegisterRawPageStatus.PARSED,
                        BuildingRegisterRawPageStatus.PARSED,
                        BuildingRegisterRawPageStatus.EMPTY,
                        BuildingRegisterRawPageStatus.EMPTY);
    }

    @Test
    @DisplayName("건축물대장 적응형 수집 처리를 검증한다")
    void parseFailureIsRecordedAndDoesNotTriggerFallback() {
        Fixture fixture = new Fixture();
        fixture.client.respond(
                BuildingRegisterEndpoint.RECAP_TITLE,
                new BuildingRegisterPageResponse(
                        BuildingRegisterEndpoint.RECAP_TITLE, PNU, 1, 100, 200, "malformed", 9, "a".repeat(64), false));

        BuildingRegisterCollectionResult result = fixture.service.collect(command(10));

        assertThat(result.status()).isEqualTo(BuildingRegisterCollectionStatus.PARSE_FAILED);
        assertThat(fixture.client.calls).containsExactly("RECAP_TITLE:1:100");
        assertThat(fixture.finalizations).containsExactly(BuildingRegisterRawPageStatus.PARSE_FAILED);
    }

    @Test
    @DisplayName("건축물대장 적응형 수집 처리를 검증한다")
    void nonFatalProviderCodeIsRecordedWithoutStoppingTheWholeRun() {
        Fixture fixture = new Fixture();
        fixture.client.respond(
                BuildingRegisterEndpoint.RECAP_TITLE,
                new ParsedBuildingRegisterPage("99", "temporary provider failure", 0, List.of()));

        BuildingRegisterCollectionResult result = fixture.service.collect(command(10));

        assertThat(result.status()).isEqualTo(BuildingRegisterCollectionStatus.PROVIDER_FAILED);
        assertThat(fixture.finalizations).containsExactly(BuildingRegisterRawPageStatus.PROVIDER_FAILED);
        assertThat(fixture.finalizedProviderStatuses).containsExactly("99");
    }

    @Test
    @DisplayName("건축물대장 적응형 수집 처리를 검증한다")
    void oversizedAtMinimumPageSizeBecomesPermanent() {
        Fixture fixture = new Fixture();
        for (int pageSize : List.of(100, 50, 25, 10)) {
            fixture.client.respond(
                    BuildingRegisterEndpoint.RECAP_TITLE,
                    new BuildingRegisterPageResponse(
                            BuildingRegisterEndpoint.RECAP_TITLE,
                            PNU,
                            1,
                            pageSize,
                            200,
                            null,
                            2_097_153,
                            "b".repeat(64),
                            true));
        }

        BuildingRegisterCollectionResult result = fixture.service.collect(command(10));

        assertThat(result.status()).isEqualTo(BuildingRegisterCollectionStatus.PERMANENT_OVERSIZED);
        assertThat(fixture.client.calls)
                .containsExactly("RECAP_TITLE:1:100", "RECAP_TITLE:1:50", "RECAP_TITLE:1:25", "RECAP_TITLE:1:10");
        assertThat(fixture.store.permanentOversized).containsExactly(false, false, false, true);
    }

    @Test
    @DisplayName("건축물대장 적응형 수집 처리를 검증한다")
    void titleFailurePreservesRecapEvidenceAndStopsBeforeBasicOverview() {
        Fixture fixture = new Fixture();
        fixture.client.respond(BuildingRegisterEndpoint.RECAP_TITLE, page(recap("20", "0"), 1));
        fixture.client.respond(
                BuildingRegisterEndpoint.TITLE,
                new BuildingRegisterPageResponse(
                        BuildingRegisterEndpoint.TITLE, PNU, 1, 100, 503, "unavailable", 11, "a".repeat(64), false));

        BuildingRegisterCollectionResult result = fixture.service.collect(command(10));

        assertThat(result.status()).isEqualTo(BuildingRegisterCollectionStatus.PROVIDER_FAILED);
        assertThat(result.recapRecords()).hasSize(1);
        assertThat(fixture.client.calls).containsExactly("RECAP_TITLE:1:100", "TITLE:1:100");
    }

    private static BuildingRegisterCollectCommand command(int maxRequests) {
        return new BuildingRegisterCollectCommand(
                COLLECTION_ID,
                REQUEST_ID,
                LocalDate.of(2026, 7, 20),
                PNU,
                1,
                BuildingRegisterCollectionStrategy.ADAPTIVE,
                maxRequests);
    }

    private static ParsedBuildingRegisterPage page(BuildingRegisterRecordSnapshotCommand record, int totalCount) {
        return new ParsedBuildingRegisterPage("00", "NORMAL SERVICE", totalCount, List.of(record));
    }

    private static BuildingRegisterRecordSnapshotCommand recap(String bcRatio, String vlRatio) {
        return record(BuildingRegisterEndpoint.RECAP_TITLE, "ROOT-1", null, "1", bcRatio, vlRatio);
    }

    private static BuildingRegisterRecordSnapshotCommand title() {
        return record(BuildingRegisterEndpoint.TITLE, "TITLE-1", "ROOT-1", "3", "20", "80");
    }

    private static BuildingRegisterRecordSnapshotCommand titleWithoutRatioCandidate() {
        return new BuildingRegisterRecordSnapshotCommand(
                0,
                PNU,
                BuildingRegisterEndpoint.TITLE,
                "TITLE-1",
                null,
                "1",
                "3",
                "0",
                "0",
                "Sample",
                "101",
                "02000",
                null,
                null,
                new BigDecimal("999"),
                null,
                null,
                null,
                2,
                1,
                740,
                LocalDate.of(2015, 3, 20),
                LocalDate.of(2026, 7, 20));
    }

    private static BuildingRegisterRecordSnapshotCommand basic() {
        return record(BuildingRegisterEndpoint.BASIC_OVERVIEW, "TITLE-1", "ROOT-1", "3", null, null);
    }

    private static BuildingRegisterRecordSnapshotCommand record(
            BuildingRegisterEndpoint endpoint,
            String key,
            String parent,
            String registerKind,
            String bcRatio,
            String vlRatio) {
        return new BuildingRegisterRecordSnapshotCommand(
                0,
                PNU,
                endpoint,
                key,
                parent,
                "1",
                registerKind,
                "0",
                "0",
                "Sample",
                "101",
                "02000",
                new BigDecimal("1000"),
                new BigDecimal("200"),
                new BigDecimal("999"),
                new BigDecimal("800"),
                decimal(bcRatio),
                decimal(vlRatio),
                2,
                1,
                740,
                LocalDate.of(2015, 3, 20),
                LocalDate.of(2026, 7, 20));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static final class Fixture {
        final FakeClient client = new FakeClient();
        final FakeSnapshotStore store = new FakeSnapshotStore();
        final List<BuildingRegisterRawPageReceiptCommand> receipts = new ArrayList<>();
        final List<String> finalizedProviderStatuses = new ArrayList<>();
        final List<BuildingRegisterRawPageStatus> finalizations = new ArrayList<>();
        final BuildingRegisterCollectionService service = new BuildingRegisterCollectionService(
                client,
                response -> client.parsed.remove(0),
                store,
                command -> {
                    receipts.add(command);
                    return 1000L + command.pageNo();
                },
                (rawPageId, snapshotId, totalCount, providerStatus, status, records) -> {
                    finalizedProviderStatuses.add(providerStatus);
                    finalizations.add(status);
                    if (totalCount != null) store.observeTotalCount(snapshotId, totalCount);
                });
    }

    private static final class FakeClient implements BuildingRegisterPageClient {
        final List<String> calls = new ArrayList<>();
        final List<Object> responses = new ArrayList<>();
        final List<ParsedBuildingRegisterPage> parsed = new ArrayList<>();

        void respond(BuildingRegisterEndpoint endpoint, ParsedBuildingRegisterPage response) {
            responses.add(endpoint);
            parsed.add(response);
        }

        void respond(BuildingRegisterEndpoint endpoint, BuildingRegisterPageResponse response) {
            responses.add(response);
        }

        void fail(String failureCode) {
            responses.add(new BuildingRegisterPageFetchException(failureCode));
        }

        @Override
        public BuildingRegisterPageResponse fetch(BuildingRegisterPageRequest request) {
            calls.add(request.endpoint() + ":" + request.pageNo() + ":" + request.pageSize());
            Object response = responses.remove(0);
            if (response instanceof BuildingRegisterPageFetchException failure) throw failure;
            if (response instanceof BuildingRegisterPageResponse page) return page;
            return new BuildingRegisterPageResponse(
                    request.endpoint(),
                    request.pnu(),
                    request.pageNo(),
                    request.pageSize(),
                    200,
                    "{}",
                    2,
                    "a".repeat(64),
                    false);
        }
    }

    private static final class FakeSnapshotStore implements BuildingRegisterEndpointSnapshotStore {
        final Map<String, BuildingRegisterCompletedPage> completed = new HashMap<>();
        final List<Integer> abandonedPageSizes = new ArrayList<>();
        final List<Integer> observedTotalCounts = new ArrayList<>();
        final List<Boolean> permanentOversized = new ArrayList<>();
        long sequence;

        @Override
        public BuildingRegisterEndpointSnapshot open(
                UUID collectionId, String pnu, BuildingRegisterEndpoint endpoint, LocalDate runDate, int pageSize) {
            return new BuildingRegisterEndpointSnapshot(++sequence, endpoint, pageSize, 1);
        }

        @Override
        public Optional<BuildingRegisterCompletedPage> completedPage(long snapshotId, int pageNo) {
            BuildingRegisterEndpoint endpoint = snapshotId == 1
                    ? BuildingRegisterEndpoint.RECAP_TITLE
                    : BuildingRegisterEndpoint.values()[(int) Math.min(snapshotId - 1, 2)];
            return Optional.ofNullable(completed.get(endpoint + ":" + currentPageSize(snapshotId) + ":" + pageNo));
        }

        private int currentPageSize(long snapshotId) {
            return snapshotId == 1 ? 100 : 50;
        }

        @Override
        public void complete(long snapshotId, int totalCount, BuildingRegisterCollectionStatus status) {}

        @Override
        public void observeTotalCount(long snapshotId, int totalCount) {
            observedTotalCounts.add(totalCount);
        }

        @Override
        public void abandonOversized(long snapshotId, int pageSize, boolean permanent) {
            abandonedPageSizes.add(pageSize);
            permanentOversized.add(permanent);
        }
    }
}
