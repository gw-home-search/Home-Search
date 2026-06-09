package com.home.application.complex;

import java.util.List;
import java.util.Objects;

import com.home.domain.complex.relation.ComplexRelationConfidence;
import com.home.domain.complex.relation.ComplexRelationType;

public record ComplexRelationCaseRecord(
	Long id,
	String caseKey,
	Long parcelId,
	String pnu,
	ComplexRelationType relationType,
	ComplexRelationConfidence relationConfidence,
	String reason,
	String classifierVersion,
	String evidenceJson,
	List<ComplexRelationCaseMember> members
) {

	public ComplexRelationCaseRecord {
		Objects.requireNonNull(id, "id is required");
		Objects.requireNonNull(caseKey, "caseKey is required");
		Objects.requireNonNull(parcelId, "parcelId is required");
		Objects.requireNonNull(pnu, "pnu is required");
		Objects.requireNonNull(relationType, "relationType is required");
		Objects.requireNonNull(relationConfidence, "relationConfidence is required");
		Objects.requireNonNull(classifierVersion, "classifierVersion is required");
		Objects.requireNonNull(evidenceJson, "evidenceJson is required");
		members = List.copyOf(members == null ? List.of() : members);
	}
}
