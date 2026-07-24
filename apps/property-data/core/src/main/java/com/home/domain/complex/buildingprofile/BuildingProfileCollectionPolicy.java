package com.home.domain.complex.buildingprofile;

import java.util.EnumSet;

public final class BuildingProfileCollectionPolicy {
    public BuildingProfileCollectionDecision decide(BuildingProfileHierarchyFacts facts) {
        EnumSet<BuildingProfileHierarchyReason> reasons = EnumSet.noneOf(BuildingProfileHierarchyReason.class);
        if (facts.complexCount() > 1) reasons.add(BuildingProfileHierarchyReason.MULTIPLE_COMPLEXES);
        if (facts.recapRootCount() > 1) reasons.add(BuildingProfileHierarchyReason.MULTIPLE_RECAP_ROOTS);
        if (facts.recapRootCount() == 0 && facts.titleCount() > 1) {
            reasons.add(BuildingProfileHierarchyReason.TITLES_WITHOUT_RECAP);
        }
        if (!facts.parentComplete()) reasons.add(BuildingProfileHierarchyReason.MISSING_PARENT);
        if (facts.parentConflict()) reasons.add(BuildingProfileHierarchyReason.PARENT_CONFLICT);
        if (facts.ambiguousGeneration()) reasons.add(BuildingProfileHierarchyReason.AMBIGUOUS_GENERATION);
        if (facts.unassignableTitle()) reasons.add(BuildingProfileHierarchyReason.UNASSIGNABLE_TITLE);
        return new BuildingProfileCollectionDecision(true, true, !reasons.isEmpty(), reasons);
    }
}
