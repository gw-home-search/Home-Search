package com.home.domain.complex.buildingprofile;

import java.util.Set;

public final class BuildingProfileCodeTransitionPolicy {
    public BuildingProfileCodeComparisonStatus compare(
            BuildingProfileLookupResult oldResult,
            Set<String> oldManagementKeys,
            BuildingProfileLookupResult newResult,
            Set<String> newManagementKeys) {
        if (oldResult == BuildingProfileLookupResult.PROVIDER_FAILED
                || oldResult == BuildingProfileLookupResult.PARSE_FAILED
                || newResult == BuildingProfileLookupResult.PROVIDER_FAILED
                || newResult == BuildingProfileLookupResult.PARSE_FAILED) {
            return BuildingProfileCodeComparisonStatus.NOT_COMPARABLE_PROVIDER_FAILURE;
        }
        if (oldResult == BuildingProfileLookupResult.EMPTY && newResult == BuildingProfileLookupResult.EMPTY) {
            return BuildingProfileCodeComparisonStatus.BOTH_EMPTY;
        }
        if (oldResult == BuildingProfileLookupResult.SUCCESS && newResult == BuildingProfileLookupResult.EMPTY) {
            return BuildingProfileCodeComparisonStatus.OLD_ONLY_SUCCESS;
        }
        if (oldResult == BuildingProfileLookupResult.EMPTY && newResult == BuildingProfileLookupResult.SUCCESS) {
            return BuildingProfileCodeComparisonStatus.NEW_ONLY_SUCCESS;
        }
        return Set.copyOf(oldManagementKeys).equals(Set.copyOf(newManagementKeys))
                ? BuildingProfileCodeComparisonStatus.CODE_TRANSITION_EQUIVALENT
                : BuildingProfileCodeComparisonStatus.BOTH_DIFFERENT;
    }
}
