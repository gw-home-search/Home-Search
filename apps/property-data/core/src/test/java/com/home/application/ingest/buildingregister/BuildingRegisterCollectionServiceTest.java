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
import org.junit.jupiter.api.Test;

class BuildingRegisterCollectionServiceTest {
    private static final String PNU = "1168010300101400001";
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174120");
    private static final UUID REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174121");

    @Test
    void adaptiveCollectionStopsAfterValidSingleRecap() {
        Fixture fixture = new Fixture();
        fixture.client.respond(BuildingRegisterEndpoint.RECAP_TITLE, page(recap("20", "80"), 1));

        BuildingRegisterCollectionResult result = fixture.service.collect(command(10));

        assertThat(fixture.client.calls).containsExactly("RECAP_TITLE:1:100");
        assertThat(result.requestCount()).isOne();
        assertThat(result.recapRecords()).hasSize(1);
        assertThat(result.titleRecords()).isEmpty();
    }

    @Test
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
    void actualRequestsConsumeBudgetBeforeFallbackCall() {
        Fixture fixture = new Fixture();
        fixture.client.respond(BuildingRegisterEndpoint.RECAP_TITLE, page(recap("20", "0"), 1));

        assertThatThrownBy(() -> fixture.service.collect(command(1)))
                .isInstanceOf(BuildingRegisterRequestBudgetExceededException.class);
        assertThat(fixture.client.calls).containsExactly("RECAP_TITLE:1:100");
    }

    @Test
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
    void completedPageIsReusedWithoutExternalRequest() {
        Fixture fixture = new Fixture();
        fixture.store.completed.put(
                "RECAP_TITLE:100:1", new BuildingRegisterCompletedPage(1, List.of(recap("20", "80"))));

        BuildingRegisterCollectionResult result = fixture.service.collect(command(10));

        assertThat(fixture.client.calls).isEmpty();
        assertThat(result.requestCount()).isZero();
        assertThat(result.recapRecords()).hasSize(1);
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
        final List<BuildingRegisterRawPageStatus> finalizations = new ArrayList<>();
        final BuildingRegisterCollectionService service = new BuildingRegisterCollectionService(
                client,
                response -> client.parsed.remove(0),
                store,
                command -> 1000L + command.pageNo(),
                (rawPageId, status, records) -> finalizations.add(status));
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

        @Override
        public BuildingRegisterPageResponse fetch(BuildingRegisterPageRequest request) {
            calls.add(request.endpoint() + ":" + request.pageNo() + ":" + request.pageSize());
            Object response = responses.remove(0);
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
        public void abandonOversized(long snapshotId, int pageSize, boolean permanent) {
            abandonedPageSizes.add(pageSize);
        }
    }
}
