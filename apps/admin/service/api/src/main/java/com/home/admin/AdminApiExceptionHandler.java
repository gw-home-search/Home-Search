package com.home.admin;

import com.home.admin.account.AdminAccountService;
import com.home.admin.internal.AdminPropertyBffController.PropertyAdminDownstreamException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AdminApiExceptionHandler {
    @ExceptionHandler(AdminAccountService.AccountNotFoundException.class)
    ProblemDetail notFound() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "관리자 계정을 찾을 수 없습니다.");
    }

    @ExceptionHandler({AdminAccountService.InvalidRoleException.class, IllegalArgumentException.class})
    ProblemDetail badRequest() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "요청 값을 확인하세요.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "이미 존재하거나 현재 상태와 충돌합니다.");
    }

    @ExceptionHandler(AdminAccountService.CannotRemoveLastAdminException.class)
    ProblemDetail lastAdmin() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "마지막 active ADMIN은 변경할 수 없습니다.");
    }

    @ExceptionHandler(PropertyAdminDownstreamException.class)
    ResponseEntity<ProblemDetail> downstream(PropertyAdminDownstreamException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .header("X-Request-Id", exception.requestId())
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Property 관리자 요청에 실패했습니다."));
    }
}
