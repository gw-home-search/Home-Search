package com.home.global.error;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

	private static final URI ERROR_TYPE = URI.create("/docs/index.html#error-code-list");
	private static final String BAD_REQUEST_TITLE = "C401";
	private static final String BAD_REQUEST_DETAIL = "Invalid parameter format.";
	private static final String INTERNAL_SERVER_ERROR_TITLE = "S500";
	private static final String INTERNAL_SERVER_ERROR_DETAIL = "Internal server error.";
	private static final String MAP_API_EXCEPTION = "MapApiException";

	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		HttpMessageNotReadableException.class
	})
	public ResponseEntity<ProblemDetail> handleBadRequest(Exception exception) {
		ProblemDetail problemDetail = createProblemDetail(
			HttpStatus.BAD_REQUEST,
			BAD_REQUEST_TITLE,
			BAD_REQUEST_DETAIL,
			MAP_API_EXCEPTION
		);

		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(problemDetail);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ProblemDetail> handleInternalServerError(Exception exception) {
		ProblemDetail problemDetail = createProblemDetail(
			HttpStatus.INTERNAL_SERVER_ERROR,
			INTERNAL_SERVER_ERROR_TITLE,
			INTERNAL_SERVER_ERROR_DETAIL,
			exception.getClass().getSimpleName()
		);

		return ResponseEntity
			.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(problemDetail);
	}

	private ProblemDetail createProblemDetail(
		HttpStatus status,
		String title,
		String detail,
		String exception
	) {
		ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
		problemDetail.setType(ERROR_TYPE);
		problemDetail.setTitle(title);
		problemDetail.setProperty("exception", exception);
		problemDetail.setProperty("timestamp", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());

		return problemDetail;
	}
}
