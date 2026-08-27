package com.example.coupon.coupon.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.example.coupon.coupon.application.CouponIssueService;
import com.example.coupon.coupon.application.CouponService;
import com.example.coupon.coupon.application.IdempotencyService;
import com.example.coupon.coupon.domain.Coupon;
import com.example.coupon.coupon.strategy.IssueResult;
import com.example.coupon.external.NotificationClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 쿠폰 API (경로는 PLAN §1.2 고정 — 변경 시 PLAN.md 먼저, 커밋 제목 `contract:`).
 *  - POST /api/coupons                          캠페인 생성 (실험 세팅용)
 *  - GET  /api/coupons/{id}                     단건 조회 (E4 읽기 부하 대상)
 *  - POST /api/coupons/{id}/issue               선착순 발급 (E6 쓰기 경합 대상)
 *  - POST /api/coupons/{id}/issue-and-notify    발급 + 외부 알림 (E8/E9 실전형)
 *  - GET  /api/users/{userId}/coupon-issues     사용자 발급 내역
 *
 * 상태코드 계약: 201 issued / 409 sold_out·already_issued·request_in_progress / 503 retry_exhausted / 404 coupon_not_found.
 * 응답의 `strategy`는 실효 설정 확인용 (k6에서 전략 실수 방지).
 * `/issue`는 `Idempotency-Key` 헤더(선택)를 지원한다 — 같은 키 재시도는 최초 응답을 그대로 재생 (E7, PLAN §1.2.1).
 * `issue-and-notify`는 미지원(IdempotencyService 클래스 주석 참고).
 */
@RestController
@RequestMapping("/api")
public class CouponController {

    private final CouponService couponService;
    private final CouponIssueService issueService;
    private final NotificationClient notificationClient;
    private final IdempotencyService idempotencyService;

    public CouponController(CouponService couponService,
                            CouponIssueService issueService,
                            NotificationClient notificationClient,
                            IdempotencyService idempotencyService) {
        this.couponService = couponService;
        this.issueService = issueService;
        this.notificationClient = notificationClient;
        this.idempotencyService = idempotencyService;
    }

    record CreateCouponRequest(@NotBlank @Size(max = 100) String name, @Positive int totalQuantity) {
    }

    record CouponResponse(long id, String name, int totalQuantity, int remainingQuantity, Instant createdAt) {
        static CouponResponse from(Coupon c) {
            return new CouponResponse(c.getId(), c.getName(), c.getTotalQuantity(), c.getRemainingQuantity(),
                    c.getCreatedAt());
        }
    }

    record IssueRequest(@NotNull @Positive Long userId) {
    }

    record UserIssueResponse(long couponId, Instant issuedAt) {
    }

    @PostMapping("/coupons")
    public ResponseEntity<CouponResponse> create(@Valid @RequestBody CreateCouponRequest request) {
        Coupon coupon = couponService.create(request.name(), request.totalQuantity());
        return ResponseEntity.created(URI.create("/api/coupons/" + coupon.getId()))
                .body(CouponResponse.from(coupon));
    }

    @GetMapping("/coupons/{id}")
    public ResponseEntity<?> get(@PathVariable long id) {
        return couponService.find(id)
                .<ResponseEntity<?>>map(c -> ResponseEntity.ok(CouponResponse.from(c)))
                .orElseGet(CouponController::notFoundBody);
    }

    @PostMapping("/coupons/{id}/issue")
    public ResponseEntity<Map<String, Object>> issue(@PathVariable long id,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                      @Valid @RequestBody IssueRequest request) {
        Supplier<ResponseEntity<Map<String, Object>>> action = () -> {
            IssueResult result = issueService.issue(id, request.userId());
            return toResponse(result, id, request.userId(), null);
        };
        return (idempotencyKey == null || idempotencyKey.isBlank())
                ? action.get()
                : idempotencyService.execute(idempotencyKey, action);
    }

    @PostMapping("/coupons/{id}/issue-and-notify")
    public ResponseEntity<Map<String, Object>> issueAndNotify(@PathVariable long id,
                                                              @Valid @RequestBody IssueRequest request) {
        IssueResult result = issueService.issue(id, request.userId());
        if (result != IssueResult.ISSUED) {
            return toResponse(result, id, request.userId(), null);
        }
        // 발급 커밋 후 트랜잭션 밖에서 호출 (PLAN §4.6(3) "좋은 버전").
        // 알림이 실패하면 GlobalExceptionHandler가 504/502를 내리지만 발급은 이미 완료 — E7 멱등성/E8-5 outbox의 동기.
        Map<String, Object> notifyResult = notificationClient.notify(request.userId(), null, null);
        return toResponse(IssueResult.ISSUED, id, request.userId(), notifyResult);
    }

    @GetMapping("/users/{userId}/coupon-issues")
    public List<UserIssueResponse> userIssues(@PathVariable long userId) {
        return couponService.userIssues(userId).stream()
                .map(i -> new UserIssueResponse(i.getCouponId(), i.getIssuedAt()))
                .toList();
    }

    private ResponseEntity<Map<String, Object>> toResponse(IssueResult result, long couponId, long userId,
                                                           Map<String, Object> notifyResult) {
        String strategy = issueService.strategyName();
        return switch (result) {
            case ISSUED -> {
                Map<String, Object> body = notifyResult == null
                        ? Map.of("result", "issued", "couponId", couponId, "userId", userId, "strategy", strategy)
                        : Map.of("result", "issued", "couponId", couponId, "userId", userId, "strategy", strategy,
                                "notify", notifyResult);
                yield ResponseEntity.status(HttpStatus.CREATED).body(body);
            }
            case SOLD_OUT -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "sold_out", "strategy", strategy));
            case ALREADY_ISSUED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "already_issued", "strategy", strategy));
            case RETRY_EXHAUSTED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "retry_exhausted", "strategy", strategy));
            case NOT_FOUND -> notFoundBody();
        };
    }

    private static ResponseEntity<Map<String, Object>> notFoundBody() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "coupon_not_found"));
    }
}
