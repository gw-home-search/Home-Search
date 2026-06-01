package com.home.application.complex;

import java.util.List;

public interface ComplexRelationCaseRepository {

	ComplexRelationCaseRecord save(
		Long parcelId,
		ComplexRelationClassification classification,
		String classifierVersion
	);

	List<ComplexRelationCaseRecord> findByParcelId(Long parcelId);
}
