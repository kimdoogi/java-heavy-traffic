package com.example.coupon.common;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** 외부 호출 실패를 상태코드로 구분해 k6/Prometheus에서 에러 유형을 나눠 볼 수 있게 한다. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 타임아웃, 연결 거부 등 I/O 계열 → 504 */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> onResourceAccess(ResourceAccessException e) {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Map.of("error", "external_unreachable", "message", String.valueOf(e.getMessage())));
    }

    /** 외부가 4xx/5xx 응답 → 502 */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, Object>> onRestClientResponse(RestClientResponseException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "external_error", "upstreamStatus", e.getStatusCode().value()));
    }
}
