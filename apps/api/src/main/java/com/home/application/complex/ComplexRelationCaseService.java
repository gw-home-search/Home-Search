package com.home.application.complex;

import java.util.List;
import java.util.Objects;

import com.home.domain.complex.relation.ComplexRelationClassification;
import com.home.domain.complex.relation.ComplexRelationClassifier;
import com.home.domain.complex.relation.ComplexTradeSpan;

public class ComplexRelationCaseService {

	private final ComplexRelationRepository relationRepository;
	private final ComplexRelationClassifier relationClassifier;
	private final ComplexRelationCaseRepository relationCaseRepository;
	private final String classifierVersion;

	public ComplexRelationCaseService(
		ComplexRelationRepository relationRepository,
		ComplexRelationClassifier relationClassifier,
		ComplexRelationCaseRepository relationCaseRepository,
		String classifierVersion
	) {
		this.relationRepository = Objects.requireNonNull(relationRepository);
		this.relationClassifier = Objects.requireNonNull(relationClassifier);
		this.relationCaseRepository = Objects.requireNonNull(relationCaseRepository);
		this.classifierVersion = requireText(classifierVersion, "classifierVersion is required");
	}

	public ComplexRelationCaseRecord classifyAndSave(Long parcelId) {
		Objects.requireNonNull(parcelId, "parcelId is required");
		List<ComplexTradeSpan> spans = relationRepository.findTradeSpansByParcelId(parcelId);
		ComplexRelationClassification classification = relationClassifier.classify(spans);
		return relationCaseRepository.save(parcelId, classification, classifierVersion);
	}

	private String requireText(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(message);
		}
		return value.trim();
	}
}
