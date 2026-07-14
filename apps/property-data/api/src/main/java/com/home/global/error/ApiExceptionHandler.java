package com.home.global.error;

import com.home.application.coordinate.override.InvalidCoordinateOverrideException;
import com.home.application.ingest.metadata.admin.InvalidMetadataAdminRequestException;
import com.home.application.place.InvalidNearbyPlaceRequestException;
import com.home.application.place.NearbyPlaceCenterUnavailableException;
import com.home.application.place.NearbyPlaceProviderUnavailableException;
import com.home.application.read.InvalidReadRequestException;
import com.home.application.read.ResourceNotFoundException;
import com.home.infrastructure.web.internaladmin.InternalAdminAuthenticationException;
import com.home.infrastructure.web.internaladmin.InternalAdminAuthorizationException;
import jakarta.validation.ConstraintViolationException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
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
    private static final String CLIENT_ERROR_TITLE = "C401";
    private static final String BAD_REQUEST_DETAIL = "Invalid parameter format.";
    private static final String UNAUTHORIZED_DETAIL = "Unauthorized admin access.";
    private static final String NOT_FOUND_TITLE = "C404";
    private static final String NOT_FOUND_DETAIL = "Resource not found.";
    private static final String INTERNAL_SERVER_ERROR_TITLE = "S500";
    private static final String INTERNAL_SERVER_ERROR_DETAIL = "Internal server error.";
    private static final String MAP_API_EXCEPTION = "MapApiException";

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        HandlerMethodValidationException.class,
        ConstraintViolationException.class,
        InvalidCoordinateOverrideException.class,
        InvalidMetadataAdminRequestException.class,
        InvalidReadRequestException.class,
        InvalidNearbyPlaceRequestException.class
    })
    public ResponseEntity<ProblemDetail> handleBadRequest(Exception exception) {
        ProblemDetail problemDetail = ApiProblemFactory.api(
                HttpStatus.BAD_REQUEST, CLIENT_ERROR_TITLE, BAD_REQUEST_DETAIL, MAP_API_EXCEPTION);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(InternalAdminAuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleInternalAdminUnauthorized(
            InternalAdminAuthenticationException exception) {
        ProblemDetail problemDetail = ApiProblemFactory.api(
                HttpStatus.UNAUTHORIZED,
                CLIENT_ERROR_TITLE,
                UNAUTHORIZED_DETAIL,
                InternalAdminAuthenticationException.class.getSimpleName());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(InternalAdminAuthorizationException.class)
    public ResponseEntity<ProblemDetail> handleInternalAdminForbidden(InternalAdminAuthorizationException exception) {
        ProblemDetail problemDetail = ApiProblemFactory.api(
                HttpStatus.FORBIDDEN,
                CLIENT_ERROR_TITLE,
                "Forbidden internal admin action.",
                InternalAdminAuthorizationException.class.getSimpleName());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException exception) {
        ProblemDetail problemDetail = ApiProblemFactory.api(
                HttpStatus.NOT_FOUND,
                NOT_FOUND_TITLE,
                NOT_FOUND_DETAIL,
                ResourceNotFoundException.class.getSimpleName());

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(NearbyPlaceCenterUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleNearbyPlaceCenterUnavailable(
            NearbyPlaceCenterUnavailableException exception) {
        ProblemDetail problemDetail = ApiProblemFactory.api(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "C422",
                "Complex display coordinate unavailable.",
                NearbyPlaceCenterUnavailableException.class.getSimpleName());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(NearbyPlaceProviderUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleNearbyPlaceProviderUnavailable(
            NearbyPlaceProviderUnavailableException exception) {
        ProblemDetail problemDetail = ApiProblemFactory.api(
                HttpStatus.SERVICE_UNAVAILABLE,
                "S503",
                "Nearby place provider unavailable.",
                NearbyPlaceProviderUnavailableException.class.getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleInternalServerError(Exception exception) {
        log.error(
                "Unhandled API exception type={}",
                exception.getClass().getSimpleName(),
                diagnosticException(exception));

        ProblemDetail problemDetail = ApiProblemFactory.api(
                HttpStatus.INTERNAL_SERVER_ERROR,
                INTERNAL_SERVER_ERROR_TITLE,
                INTERNAL_SERVER_ERROR_DETAIL,
                exception.getClass().getSimpleName());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    private RuntimeException diagnosticException(Exception exception) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return sanitizedDiagnostic(exception, visited);
    }

    private RuntimeException sanitizedDiagnostic(Throwable exception, Set<Throwable> visited) {
        if (!visited.add(exception)) {
            return new RuntimeException(
                    "Sanitized cyclic cause type=" + exception.getClass().getSimpleName());
        }
        Throwable cause = exception.getCause();
        RuntimeException diagnostic = new RuntimeException(
                "Sanitized exception type=" + exception.getClass().getSimpleName(),
                cause == null ? null : sanitizedDiagnostic(cause, visited));
        diagnostic.setStackTrace(exception.getStackTrace());
        for (Throwable suppressed : exception.getSuppressed()) {
            diagnostic.addSuppressed(sanitizedDiagnostic(suppressed, visited));
        }
        return diagnostic;
    }
}
