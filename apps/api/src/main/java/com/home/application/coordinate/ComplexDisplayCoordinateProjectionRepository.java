package com.home.application.coordinate;

import java.util.List;

public interface ComplexDisplayCoordinateProjectionRepository {

	List<ComplexDisplayCoordinateProjectionTarget> findProjectionTargets(int limit);

	void saveDisplayCoordinate(ComplexDisplayCoordinateCommand command);
}
