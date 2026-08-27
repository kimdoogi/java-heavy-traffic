package com.example.coupon.coupon.api;

import java.util.Map;

import com.example.coupon.coupon.application.IdempotencyInProgressException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 쿠폰 API 전용 예외 → 계약 형태({"error": ...}) 매핑. 포화 시(Hikari 고갈, DB/Redis 단절)
 * Boot 기본 500 본문 대신 k6가 error 필드로 분류할 수 있는 본문을 유지한다.
 * common/GlobalExceptionHandler는 공유 경로(D-005)라 건드리지 않고 이 컨트롤러에만 스코프를 좁혔다 —
 * 외부 호출 실패(504/502)는 여전히 GlobalExceptionHandler가 처리한다.
 */
@RestControllerAdvice(assignableTypes = CouponController.class)
public class CouponApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CouponApiExceptionHandler.class);

    /** 커넥션 풀 고갈·DB/Redis 단절 등 저장소 계열 → 503 (일시 과부하 신호). 포화 시 로그 폭주를 피해 한 줄만 남긴다. */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> onDataAccess(DataAccessException e) {
        log.warn("storage failure on coupon api: {}", e.toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "storage_unavailable"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> onIllegalState(IllegalStateException e) {
        log.error("internal error on coupon api", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal_error"));
    }

    /** 같은 Idempotency-Key로 아직 처리 중인 동시 재시도 (E7, PLAN §1.2.1). strategy 필드 없음 — 전략 계층 도달 전에 끝난다. */
    @ExceptionHandler(IdempotencyInProgressException.class)
    public ResponseEntity<Map<String, Object>> onIdempotencyInProgress(IdempotencyInProgressException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "request_in_progress"));
    }
}
