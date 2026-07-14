package com.home.application.complex;

import com.home.domain.complex.relation.ComplexTradeSpan;
import java.util.List;

public interface ComplexRelationRepository {

    List<ComplexTradeSpan> findTradeSpansByParcelId(Long parcelId);
}
