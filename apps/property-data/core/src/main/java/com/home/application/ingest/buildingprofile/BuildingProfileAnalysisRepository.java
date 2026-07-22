package com.home.application.ingest.buildingprofile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface BuildingProfileAnalysisRepository {
    boolean startOrLoad(BuildingProfileAnalysisCommand command);

    List<BuildingProfileAnalysisRecord> records(UUID parseRunId);

    List<BuildingProfileAnalysisComplex> complexes(UUID collectionId);

    Map<String, Double> sampleWeights(UUID collectionId);

    Map<String, String> sampleStrata(UUID collectionId);

    double operationalCompletion(UUID collectionId);

    Map<String, Double> operationalCompletionByStratum(UUID collectionId);

    BuildingProfileReportStats reportStats(UUID collectionId, UUID parseRunId);

    void recordAssignments(UUID analysisRunId, List<BuildingProfileAssignmentEvidence> assignments);

    void recordComplexMatches(UUID analysisRunId, UUID collectionId, List<BuildingProfileComplexMatchEvidence> matches);

    void recordComparisons(UUID analysisRunId, List<BuildingProfileComparisonEvidence> comparisons);

    void recordFieldQuality(UUID analysisRunId, List<BuildingProfileFieldQualityEvidence> quality);

    void complete(UUID analysisRunId, String reportManifestJson);
}
