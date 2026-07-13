package com.home.user.web;
import com.home.application.user.UserNotFoundException;
import com.home.domain.user.token.InvalidRefreshTokenException;
import com.home.application.favorite.InvalidComplexIdException;
import com.home.application.favorite.InvalidPaginationException;
import com.home.domain.user.favorite.FavoriteLimitReachedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
@RestControllerAdvice
public class UserApiExceptionHandler {
 @ExceptionHandler({InvalidRefreshTokenException.class,UserNotFoundException.class,NumberFormatException.class})
 ProblemDetail unauthorized(){return UserProblemDetails.create(HttpStatus.UNAUTHORIZED,"인증이 필요합니다","Authentication is required.","AUTHENTICATION_REQUIRED","AuthenticationException");}
 @ExceptionHandler(InvalidComplexIdException.class)
 ProblemDetail invalidComplex(){return UserProblemDetails.create(HttpStatus.BAD_REQUEST,"잘못된 단지 식별자입니다","complexId must be a positive integer.","INVALID_COMPLEX_ID","InvalidComplexIdException");}
 @ExceptionHandler(InvalidPaginationException.class)
 ProblemDetail invalidPagination(){return UserProblemDetails.create(HttpStatus.BAD_REQUEST,"잘못된 페이지 요청입니다","page must be non-negative and size must be between 1 and 100.","INVALID_PAGINATION","InvalidPaginationException");}
 @ExceptionHandler(FavoriteLimitReachedException.class)
 ProblemDetail favoriteLimit(){return UserProblemDetails.create(HttpStatus.CONFLICT,"관심 단지 저장 한도를 초과했습니다","A user may save at most 200 favorite complexes.","FAVORITE_LIMIT_REACHED","FavoriteLimitReachedException");}
}
