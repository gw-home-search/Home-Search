package com.home.application.complex;

import java.util.List;

import com.home.domain.complex.relation.ComplexTradeSpan;

public interface ComplexRelationRepository {

	List<ComplexTradeSpan> findTradeSpansByParcelId(Long parcelId);
}
