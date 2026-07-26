package com.home.user.web;

import com.home.application.favorite.InvalidComplexIdException;
import com.home.application.favorite.InvalidPaginationException;
import com.home.application.insight.EmailConsentRequiredException;
import com.home.application.insight.InvalidInsightSubscriptionException;
import com.home.application.user.UserNotFoundException;
import com.home.domain.user.favorite.FavoriteLimitReachedException;
import com.home.domain.user.token.InvalidRefreshTokenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class UserApiExceptionHandler {
    @ExceptionHandler({InvalidRefreshTokenException.class, UserNotFoundException.class, NumberFormatException.class})
    ProblemDetail unauthorized() {
        return UserProblemDetails.create(
                HttpStatus.UNAUTHORIZED,
                "인증이 필요합니다",
                "Authentication is required.",
                "AUTHENTICATION_REQUIRED",
                "AuthenticationException");
    }

    @ExceptionHandler(InvalidComplexIdException.class)
    ProblemDetail invalidComplex() {
        return UserProblemDetails.create(
                HttpStatus.BAD_REQUEST,
                "잘못된 단지 식별자입니다",
                "complexId must be a positive integer.",
                "INVALID_COMPLEX_ID",
                "InvalidComplexIdException");
    }

    @ExceptionHandler(InvalidPaginationException.class)
    ProblemDetail invalidPagination() {
        return UserProblemDetails.create(
                HttpStatus.BAD_REQUEST,
                "잘못된 페이지 요청입니다",
                "page must be non-negative and size must be between 1 and 100.",
                "INVALID_PAGINATION",
                "InvalidPaginationException");
    }

    @ExceptionHandler(FavoriteLimitReachedException.class)
    ProblemDetail favoriteLimit() {
        return UserProblemDetails.create(
                HttpStatus.CONFLICT,
                "관심 단지 저장 한도를 초과했습니다",
                "A user may save at most 200 favorite complexes.",
                "FAVORITE_LIMIT_REACHED",
                "FavoriteLimitReachedException");
    }

    @ExceptionHandler(InvalidInsightSubscriptionException.class)
    ProblemDetail invalidInsightSubscription() {
        return UserProblemDetails.create(
                HttpStatus.BAD_REQUEST,
                "잘못된 인사이트 구독 설정입니다",
                "regionCodes must contain at most five distinct supported SIDO codes.",
                "INVALID_INSIGHT_SUBSCRIPTION",
                "InvalidInsightSubscriptionException");
    }

    @ExceptionHandler(EmailConsentRequiredException.class)
    ProblemDetail emailConsentRequired() {
        return UserProblemDetails.create(
                HttpStatus.CONFLICT,
                "이메일 수신 동의가 필요합니다",
                "A current account email and explicit consent are required.",
                "EMAIL_CONSENT_REQUIRED",
                "EmailConsentRequiredException");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail invalidArgumentType(MethodArgumentTypeMismatchException exception) {
        return exception.getParameter().getParameterType() == long.class ? invalidComplex() : invalidPagination();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidArgument(MethodArgumentNotValidException exception) {
        if (exception.getParameter().getParameterType() == InsightController.SubscriptionRequest.class) {
            return invalidInsightSubscription();
        }
        return exception.getParameter().getParameterType() == long.class ? invalidComplex() : invalidPagination();
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail internalServerError() {
        return UserProblemDetails.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "서버 오류가 발생했습니다",
                "An unexpected server error occurred.",
                "INTERNAL_SERVER_ERROR",
                "InternalServerException");
    }
}
