package com.home.application.ingest.metadata;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * ODC와 building API metadata 후보의 우선순위, 병합, 충돌 판정을 소유하는 application policy입니다.
 */
public class ComplexMetadataResolutionPolicy {

	public ComplexMetadataResolution resolve(
		String pnu,
		boolean buildingFallbackEnabled,
		ComplexMetadataResolution odcloud,
		Supplier<ComplexMetadataResolution> buildingResolver
	) {
		Objects.requireNonNull(odcloud, "odcloud resolution is required");
		if (odcloud.status().isAmbiguous() || odcloud.status().isFailed()) {
			return odcloud;
		}
		if (hasMetadata(odcloud)) {
			if (!buildingFallbackEnabled) {
				return odcloud;
			}
			ComplexMetadataResolution building = Objects.requireNonNull(buildingResolver, "buildingResolver is required")
				.get();
			if (hasMetadata(building)) {
				if (conflicts(odcloud.metadata(), building.metadata())) {
					return ComplexMetadataResolution.ambiguous("ODC+BLD",
						"complex metadata source conflict pnu=" + pnu)
						.withLookupEvidence(odcloud.lookupEvidence());
				}
				return ComplexMetadataResolution.classify("ODC+BLD", merge(odcloud.metadata(), building.metadata()))
					.withLookupEvidence(odcloud.lookupEvidence());
			}
			if (building.status().isAmbiguous()) {
				return odcloud;
			}
			return odcloud;
		}
		if (!buildingFallbackEnabled) {
			return odcloud;
		}
		return Objects.requireNonNull(buildingResolver, "buildingResolver is required").get();
	}

	private boolean hasMetadata(ComplexMetadataResolution resolution) {
		return resolution.status().isResolvedLike();
	}

	private ComplexMetadata merge(ComplexMetadata odcloudMetadata, ComplexMetadata buildingMetadata) {
		if (odcloudMetadata == null) {
			return buildingMetadata;
		}
		if (buildingMetadata == null) {
			return odcloudMetadata;
		}
		return new ComplexMetadata(
			firstNonNull(odcloudMetadata.dongCnt(), buildingMetadata.dongCnt()),
			firstNonNull(odcloudMetadata.unitCnt(), buildingMetadata.unitCnt()),
			firstNonNull(odcloudMetadata.platArea(), buildingMetadata.platArea()),
			firstNonNull(odcloudMetadata.archArea(), buildingMetadata.archArea()),
			firstNonNull(odcloudMetadata.totArea(), buildingMetadata.totArea()),
			firstNonNull(odcloudMetadata.bcRat(), buildingMetadata.bcRat()),
			firstNonNull(odcloudMetadata.vlRat(), buildingMetadata.vlRat()),
			firstNonNull(odcloudMetadata.useDate(), buildingMetadata.useDate())
		);
	}

	private boolean conflicts(ComplexMetadata first, ComplexMetadata second) {
		if (first == null || second == null) {
			return false;
		}
		return conflict(first.dongCnt(), second.dongCnt())
			|| conflict(first.unitCnt(), second.unitCnt())
			|| conflict(first.platArea(), second.platArea())
			|| conflict(first.archArea(), second.archArea())
			|| conflict(first.totArea(), second.totArea())
			|| conflict(first.bcRat(), second.bcRat())
			|| conflict(first.vlRat(), second.vlRat())
			|| conflict(first.useDate(), second.useDate());
	}

	private boolean conflict(Object first, Object second) {
		if (first instanceof BigDecimal firstNumber && second instanceof BigDecimal secondNumber) {
			return firstNumber.compareTo(secondNumber) != 0;
		}
		return first != null && second != null && !first.equals(second);
	}

	private <T> T firstNonNull(T first, T second) {
		return first != null ? first : second;
	}
}
