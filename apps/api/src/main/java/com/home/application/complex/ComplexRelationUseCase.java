package com.home.application.complex;

import java.util.Objects;

public class ComplexRelationUseCase {

	private final ComplexRelationRepository repository;
	private final ComplexRelationClassifier classifier;

	public ComplexRelationUseCase(ComplexRelationRepository repository, ComplexRelationClassifier classifier) {
		this.repository = Objects.requireNonNull(repository);
		this.classifier = Objects.requireNonNull(classifier);
	}

	public ComplexRelationClassification classifyParcel(Long parcelId) {
		Objects.requireNonNull(parcelId, "parcelId is required");
		return classifier.classify(repository.findTradeSpansByParcelId(parcelId));
	}
}
