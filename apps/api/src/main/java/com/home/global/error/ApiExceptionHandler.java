package com.home.global.error;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import com.home.application.coordinate.override.InvalidCoordinateOverrideException;
import com.home.application.read.InvalidReadRequestException;
import com.home.infrastructure.web.admin.AdminCoordinateAccessDeniedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
	private static final URI ERROR_TYPE = URI.create("/docs/index.html#error-code-list");
	private static final String BAD_REQUEST_TITLE = "C401";
	private static final String BAD_REQUEST_DETAIL = "Invalid parameter format.";
	private static final String UNAUTHORIZED_DETAIL = "Unauthorized admin access.";
	private static final String NOT_FOUND_TITLE = "C404";
	private static final String NOT_FOUND_DETAIL = "Resource not found.";
	private static final String INTERNAL_SERVER_ERROR_TITLE = "S500";
	private static final String INTERNAL_SERVER_ERROR_DETAIL = "Internal server error.";
	private static final String MAP_API_EXCEPTION = "MapApiException";
	private static final DateTimeFormatter ERROR_TIMESTAMP_FORMATTER =
		DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		HttpMessageNotReadableException.class,
		MissingServletRequestParameterException.class,
		MethodArgumentTypeMismatchException.class,
		HandlerMethodValidationException.class,
		InvalidCoordinateOverrideException.class,
		InvalidReadRequestException.class
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

	@ExceptionHandler(AdminCoordinateAccessDeniedException.class)
	public ResponseEntity<ProblemDetail> handleUnauthorized(AdminCoordinateAccessDeniedException exception) {
		ProblemDetail problemDetail = createProblemDetail(
			HttpStatus.UNAUTHORIZED,
			BAD_REQUEST_TITLE,
			UNAUTHORIZED_DETAIL,
			AdminCoordinateAccessDeniedException.class.getSimpleName()
		);

		return ResponseEntity
			.status(HttpStatus.UNAUTHORIZED)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(problemDetail);
	}

	@ExceptionHandler(ResourceNotFoundException.class)
	public ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException exception) {
		ProblemDetail problemDetail = createProblemDetail(
			HttpStatus.NOT_FOUND,
			NOT_FOUND_TITLE,
			NOT_FOUND_DETAIL,
			ResourceNotFoundException.class.getSimpleName()
		);

		return ResponseEntity
			.status(HttpStatus.NOT_FOUND)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(problemDetail);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ProblemDetail> handleInternalServerError(Exception exception) {
		log.error("Unhandled API exception type={}", exception.getClass().getSimpleName(), diagnosticException(exception));

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

	private RuntimeException diagnosticException(Exception exception) {
		RuntimeException diagnostic = new RuntimeException("Unhandled API exception");
		diagnostic.setStackTrace(exception.getStackTrace());
		return diagnostic;
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
		problemDetail.setProperty(
			"timestamp",
			OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
				.format(ERROR_TIMESTAMP_FORMATTER)
		);

		return problemDetail;
	}
}
