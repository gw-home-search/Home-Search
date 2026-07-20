package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRegisterCollectionPolicy;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import com.home.domain.complex.buildingregister.BuildingRegisterFollowUpDecision;
import com.home.domain.complex.buildingregister.BuildingRegisterRawPageStatus;
import com.home.domain.complex.buildingregister.BuildingRegisterRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class BuildingRegisterCollectionService {
    private static final int[] PAGE_SIZES = {100, 50, 25, 10};
    private static final String EMPTY_BODY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final BuildingRegisterPageClient client;
    private final BuildingRegisterPageParser parser;
    private final BuildingRegisterEndpointSnapshotStore snapshots;
    private final BuildingRegisterRawPageReceiver receiver;
    private final BuildingRegisterRawPageCompletion completion;
    private final BuildingRegisterCollectionPolicy policy = new BuildingRegisterCollectionPolicy();

    public BuildingRegisterCollectionService(
            BuildingRegisterPageClient client,
            BuildingRegisterPageParser parser,
            BuildingRegisterEndpointSnapshotStore snapshots,
            BuildingRegisterRawPageReceiver receiver,
            BuildingRegisterRawPageCompletion completion) {
        this.client = Objects.requireNonNull(client);
        this.parser = Objects.requireNonNull(parser);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.receiver = Objects.requireNonNull(receiver);
        this.completion = Objects.requireNonNull(completion);
    }

    public BuildingRegisterCollectionResult collect(BuildingRegisterCollectCommand command) {
        RequestBudget budget = new RequestBudget(command.maxRequests());
        EndpointResult recap = collectEndpoint(command, BuildingRegisterEndpoint.RECAP_TITLE, budget);
        if (recap.status() != BuildingRegisterCollectionStatus.COLLECTED) {
            return result(recap.status(), budget.used(), recap.records(), List.of(), List.of(), null);
        }
        BuildingRegisterFollowUpDecision decision = policy.afterRecap(
                command.strategy(),
                recap.records().stream().map(this::domainRecord).toList(),
                command.pnuComplexCount());
        EndpointResult titles = decision.fetchTitles()
                ? collectEndpoint(command, BuildingRegisterEndpoint.TITLE, budget)
                : EndpointResult.collected(List.of());
        if (titles.status() != BuildingRegisterCollectionStatus.COLLECTED) {
            return result(titles.status(), budget.used(), recap.records(), titles.records(), List.of(), decision);
        }
        boolean multipleStandaloneCandidates =
                recap.records().isEmpty() && titles.records().size() > 1;
        EndpointResult overview = decision.fetchBasicOverview() || multipleStandaloneCandidates
                ? collectEndpoint(command, BuildingRegisterEndpoint.BASIC_OVERVIEW, budget)
                : EndpointResult.collected(List.of());
        return result(
                overview.status(), budget.used(), recap.records(), titles.records(), overview.records(), decision);
    }

    private EndpointResult collectEndpoint(
            BuildingRegisterCollectCommand command, BuildingRegisterEndpoint endpoint, RequestBudget budget) {
        for (int pageSize : PAGE_SIZES) {
            BuildingRegisterEndpointSnapshot snapshot =
                    snapshots.open(command.collectionId(), command.pnu(), endpoint, command.runDate(), pageSize);
            List<BuildingRegisterRecordSnapshotCommand> records = new ArrayList<>();
            int pageNo = 1;
            while (true) {
                var completed = snapshots.completedPage(snapshot.id(), pageNo);
                if (completed.isPresent()) {
                    records.addAll(completed.get().records());
                    if (pageNo * pageSize >= completed.get().totalCount()) {
                        snapshots.complete(
                                snapshot.id(),
                                completed.get().totalCount(),
                                BuildingRegisterCollectionStatus.COLLECTED);
                        return EndpointResult.collected(records);
                    }
                    pageNo++;
                    continue;
                }

                budget.consume();
                BuildingRegisterPageResponse response;
                try {
                    response =
                            client.fetch(new BuildingRegisterPageRequest(endpoint, command.pnu(), pageNo, pageSize));
                } catch (BuildingRegisterPageFetchException failure) {
                    long rawPageId = receiver.receive(new BuildingRegisterRawPageReceiptCommand(
                            snapshot.id(),
                            command.requestId(),
                            pageNo,
                            snapshot.attemptNo(),
                            null,
                            EMPTY_BODY_SHA256,
                            0,
                            null,
                            failure.failureCode()));
                    completion.complete(
                            rawPageId,
                            snapshot.id(),
                            null,
                            failure.failureCode(),
                            BuildingRegisterRawPageStatus.PROVIDER_FAILED,
                            List.of());
                    snapshots.complete(snapshot.id(), 0, BuildingRegisterCollectionStatus.PROVIDER_FAILED);
                    return new EndpointResult(BuildingRegisterCollectionStatus.PROVIDER_FAILED, records);
                }
                long rawPageId = receiver.receive(receipt(command, snapshot, response));
                if (response.oversized()) {
                    completion.complete(
                            rawPageId, snapshot.id(), null, null, BuildingRegisterRawPageStatus.OVERSIZED, List.of());
                    boolean permanent = pageSize == PAGE_SIZES[PAGE_SIZES.length - 1];
                    snapshots.abandonOversized(snapshot.id(), pageSize, permanent);
                    if (permanent)
                        return new EndpointResult(BuildingRegisterCollectionStatus.PERMANENT_OVERSIZED, records);
                    break;
                }
                if (!response.httpSuccessful()) {
                    completion.complete(
                            rawPageId,
                            snapshot.id(),
                            null,
                            null,
                            BuildingRegisterRawPageStatus.PROVIDER_FAILED,
                            List.of());
                    snapshots.complete(snapshot.id(), 0, BuildingRegisterCollectionStatus.PROVIDER_FAILED);
                    if (response.authenticationOrQuotaFailure()) {
                        throw new BuildingRegisterFatalProviderException(Integer.toString(response.httpStatus()));
                    }
                    return new EndpointResult(BuildingRegisterCollectionStatus.PROVIDER_FAILED, records);
                }

                ParsedBuildingRegisterPage parsed;
                try {
                    parsed = parser.parse(response);
                } catch (RuntimeException exception) {
                    completion.complete(
                            rawPageId, snapshot.id(), null, null, BuildingRegisterRawPageStatus.PARSE_FAILED, List.of());
                    snapshots.complete(snapshot.id(), 0, BuildingRegisterCollectionStatus.PARSE_FAILED);
                    return new EndpointResult(BuildingRegisterCollectionStatus.PARSE_FAILED, records);
                }
                if (!parsed.providerSuccessful()) {
                    completion.complete(
                            rawPageId,
                            snapshot.id(),
                            null,
                            parsed.resultCode(),
                            BuildingRegisterRawPageStatus.PROVIDER_FAILED,
                            List.of());
                    snapshots.complete(
                            snapshot.id(), parsed.totalCount(), BuildingRegisterCollectionStatus.PROVIDER_FAILED);
                    if (parsed.authenticationOrQuotaFailure()) {
                        throw new BuildingRegisterFatalProviderException(parsed.resultCode());
                    }
                    return new EndpointResult(BuildingRegisterCollectionStatus.PROVIDER_FAILED, records);
                }
                BuildingRegisterRawPageStatus rawStatus = parsed.records().isEmpty()
                        ? BuildingRegisterRawPageStatus.EMPTY
                        : BuildingRegisterRawPageStatus.PARSED;
                completion.complete(
                        rawPageId,
                        snapshot.id(),
                        parsed.totalCount(),
                        parsed.resultCode(),
                        rawStatus,
                        parsed.records());
                records.addAll(parsed.records());
                if (pageNo * pageSize >= parsed.totalCount()) {
                    snapshots.complete(snapshot.id(), parsed.totalCount(), BuildingRegisterCollectionStatus.COLLECTED);
                    return EndpointResult.collected(records);
                }
                pageNo++;
            }
        }
        return new EndpointResult(BuildingRegisterCollectionStatus.PERMANENT_OVERSIZED, List.of());
    }

    private BuildingRegisterRawPageReceiptCommand receipt(
            BuildingRegisterCollectCommand command,
            BuildingRegisterEndpointSnapshot snapshot,
            BuildingRegisterPageResponse response) {
        return new BuildingRegisterRawPageReceiptCommand(
                snapshot.id(),
                command.requestId(),
                response.pageNo(),
                snapshot.attemptNo(),
                response.body(),
                response.bodySha256(),
                Math.toIntExact(response.byteCount()),
                response.httpStatus(),
                null);
    }

    private BuildingRegisterRecord domainRecord(BuildingRegisterRecordSnapshotCommand record) {
        return new BuildingRegisterRecord(
                record.managementKey(),
                record.parentManagementKey(),
                integer(record.registerKindCode()),
                record.mainAttachedCode(),
                record.mainPurposeCode(),
                record.platArea(),
                record.archArea(),
                record.totalArea(),
                record.floorRatioEstimateTotalArea(),
                record.buildingCoverageRatio(),
                record.floorAreaRatio());
    }

    private int integer(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private BuildingRegisterCollectionResult result(
            BuildingRegisterCollectionStatus status,
            int requests,
            List<BuildingRegisterRecordSnapshotCommand> recap,
            List<BuildingRegisterRecordSnapshotCommand> titles,
            List<BuildingRegisterRecordSnapshotCommand> overview,
            BuildingRegisterFollowUpDecision decision) {
        return new BuildingRegisterCollectionResult(
                status, requests, recap, titles, overview, decision == null ? null : decision.fallbackFields());
    }

    private record EndpointResult(
            BuildingRegisterCollectionStatus status, List<BuildingRegisterRecordSnapshotCommand> records) {
        static EndpointResult collected(List<BuildingRegisterRecordSnapshotCommand> records) {
            return new EndpointResult(BuildingRegisterCollectionStatus.COLLECTED, records);
        }
    }

    private static final class RequestBudget {
        private final int max;
        private int used;

        private RequestBudget(int max) {
            this.max = max;
        }

        void consume() {
            if (used >= max) throw new BuildingRegisterRequestBudgetExceededException(max);
            used++;
        }

        int used() {
            return used;
        }
    }
}
