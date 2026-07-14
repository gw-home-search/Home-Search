package com.home.application.ingest.buildingmetadata;

import com.home.domain.complex.buildingmetadata.BuildingMetadataSourceKind;

@FunctionalInterface
public interface BuildingMetadataSourceClient {
    BuildingMetadataSourceResponse fetch(BuildingMetadataSourceKind sourceKind, String pnu);

    default boolean isConfigured() {
        return true;
    }

    static BuildingMetadataSourceClient noop() {
        return new BuildingMetadataSourceClient() {
            @Override
            public BuildingMetadataSourceResponse fetch(BuildingMetadataSourceKind sourceKind, String pnu) {
                throw new IllegalStateException("building metadata source client is not configured");
            }

            @Override
            public boolean isConfigured() {
                return false;
            }
        };
    }
}
