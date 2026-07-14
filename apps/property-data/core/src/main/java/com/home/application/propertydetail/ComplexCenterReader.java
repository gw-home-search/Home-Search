package com.home.application.propertydetail;

import java.util.Optional;

public interface ComplexCenterReader {

    Optional<ComplexCenter> findComplexCenter(Long complexId);
}
