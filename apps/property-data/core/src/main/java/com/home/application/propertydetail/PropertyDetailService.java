package com.home.application.propertydetail;

import java.util.List;
import java.util.Objects;

import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.ParcelDetailResult;
import com.home.application.read.ResourceNotFoundException;

import org.springframework.stereotype.Service;

@Service
public class PropertyDetailService {

	private final PropertyDetailReader reader;

	public PropertyDetailService(PropertyDetailReader reader) {
		this.reader = Objects.requireNonNull(reader);
	}

	public ParcelDetailResult getParcelDetail(Long parcelId, Long complexId) {
		return reader.findParcelDetail(parcelId, complexId)
			.orElseThrow(() -> new ResourceNotFoundException("parcel detail not found: " + parcelId));
	}

	public List<ComplexSummaryResult> getParcelComplexes(Long parcelId) {
		return reader.findParcelComplexes(parcelId)
			.orElseThrow(() -> new ResourceNotFoundException("parcel not found: " + parcelId));
	}

	public ParcelDetailResult getComplexDetail(Long complexId) {
		return reader.findComplexDetail(complexId)
			.orElseThrow(() -> new ResourceNotFoundException("complex detail not found: " + complexId));
	}
}
