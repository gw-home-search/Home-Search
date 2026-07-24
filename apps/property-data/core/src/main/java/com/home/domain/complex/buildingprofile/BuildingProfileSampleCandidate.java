package com.home.domain.complex.buildingprofile;

public record BuildingProfileSampleCandidate(
        String pnu,
        String regionCode,
        int complexCount,
        int observedTitleCount,
        String legalTransitionGroup,
        boolean hierarchyRisk,
        boolean metadataControl) {
    public BuildingProfileSampleCandidate {
        if (pnu == null || !pnu.matches("[0-9]{19}")) throw new IllegalArgumentException("pnu must be 19 digits");
        if (regionCode == null || regionCode.isBlank()) throw new IllegalArgumentException("regionCode is required");
        if (complexCount <= 0 || observedTitleCount < 0) throw new IllegalArgumentException("invalid candidate counts");
        if (legalTransitionGroup != null
                && !legalTransitionGroup.equals("INCHEON")
                && !legalTransitionGroup.equals("GWANGJU_JEONNAM")) {
            throw new IllegalArgumentException("unsupported legal transition group");
        }
    }

    public boolean legalCodeTransition() {
        return legalTransitionGroup != null;
    }
}
