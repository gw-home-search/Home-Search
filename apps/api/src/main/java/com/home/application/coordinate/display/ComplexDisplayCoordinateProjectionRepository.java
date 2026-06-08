package com.home.application.coordinate.display;

import java.util.List;

public interface ComplexDisplayCoordinateProjectionRepository {

	List<ComplexDisplayCoordinateProjectionTarget> findProjectionTargets(int limit);

	void saveDisplayCoordinate(ComplexDisplayCoordinateCommand command);
}
