package com.home.application.coordinate.caseflow;

import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

public class CoordinateResolutionCommitter {

	private final ComplexCoordinateExceptionRepository repository;

	public CoordinateResolutionCommitter(ComplexCoordinateExceptionRepository repository) {
		this.repository = Objects.requireNonNull(repository);
	}

	@Transactional
	public void commit(CoordinateResolutionCommitCommand command) {
		Objects.requireNonNull(command, "command is required");
		command.coordinates().forEach(repository::saveResolvedDisplayCoordinate);
		repository.saveCaseUpdate(command.caseUpdate());
	}
}
