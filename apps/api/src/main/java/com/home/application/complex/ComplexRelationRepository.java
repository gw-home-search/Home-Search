package com.home.application.complex;

import java.util.List;

public interface ComplexRelationRepository {

	List<ComplexTradeSpan> findTradeSpansByParcelId(Long parcelId);
}
