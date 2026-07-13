package com.home.application.propertydetail;

import java.util.List;
import java.util.Optional;

import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.ParcelDetailResult;

public interface PropertyDetailReader {

	Optional<ParcelDetailResult> findParcelDetail(Long parcelId, Long complexId);

	Optional<List<ComplexSummaryResult>> findParcelComplexes(Long parcelId);

	Optional<ParcelDetailResult> findComplexDetail(Long complexId);
}
