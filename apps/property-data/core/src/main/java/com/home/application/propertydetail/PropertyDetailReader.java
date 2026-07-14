package com.home.application.propertydetail;

import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.ParcelDetailResult;
import java.util.List;
import java.util.Optional;

public interface PropertyDetailReader {

    Optional<ParcelDetailResult> findParcelDetail(Long parcelId, Long complexId);

    Optional<List<ComplexSummaryResult>> findParcelComplexes(Long parcelId);

    Optional<ParcelDetailResult> findComplexDetail(Long complexId);
}
