package com.example.coupon.coupon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.coupon.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 레벨 계약 검증 (journal 2026-08-26 계약 확정본).
 * 기본 전략(redis) 경로로 상태코드·본문 형태를 고정한다 — k6 시나리오(50-flash-sale.js)가 이 계약을 소비한다.
 */
@Import(TestcontainersConfiguration.class)
// external.base-url: 호스트에 mock-external이 떠 있으면 알림이 진짜 성공해버리므로 항상 connection refused가 나는 포트로 고정.
// coupon.issue.strategy: 셸에 export된 ISSUE_STRATEGY가 ${ISSUE_STRATEGY:redis}로 흘러들면 다른 전략으로 돌므로 명시 고정.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"external.base-url=http://localhost:1", "coupon.issue.strategy=redis"})
class CouponApiContractTest {

    @Value("${local.server.port}")
    int port;

    @Autowired StringRedisTemplate redisTemplate;

    RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {
                    // 4xx/5xx도 본문을 검증해야 하므로 예외로 바꾸지 않는다
                })
                .build();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> post(String uri, Map<String, Object> body) {
        return client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);
    }

    @Test
    void 생성_조회_발급_중복_품절_404_계약() {
        // 생성
        ResponseEntity<Map> created = post("/api/coupons", Map.of("name", "contract", "totalQuantity", 2));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        long couponId = ((Number) created.getBody().get("id")).longValue();
        assertThat(created.getBody()).containsEntry("totalQuantity", 2).containsEntry("remainingQuantity", 2);

        // 조회
        ResponseEntity<Map> got = client.get().uri("/api/coupons/" + couponId).retrieve().toEntity(Map.class);
        assertThat(got.getStatusCode().value()).isEqualTo(200);
        assertThat(got.getBody()).containsEntry("name", "contract");

        // 발급 201
        ResponseEntity<Map> issued = post("/api/coupons/" + couponId + "/issue", Map.of("userId", 1));
        assertThat(issued.getStatusCode().value()).isEqualTo(201);
        assertThat(issued.getBody()).containsEntry("result", "issued").containsEntry("strategy", "redis");

        // 같은 사용자 재요청 409 already_issued
        ResponseEntity<Map> dup = post("/api/coupons/" + couponId + "/issue", Map.of("userId", 1));
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
        assertThat(dup.getBody()).containsEntry("error", "already_issued");

        // 남은 1개 소진 후 품절 409 sold_out
        assertThat(post("/api/coupons/" + couponId + "/issue", Map.of("userId", 2)).getStatusCode().value())
                .isEqualTo(201);
        ResponseEntity<Map> soldOut = post("/api/coupons/" + couponId + "/issue", Map.of("userId", 3));
        assertThat(soldOut.getStatusCode().value()).isEqualTo(409);
        assertThat(soldOut.getBody()).containsEntry("error", "sold_out");

        // 발급 내역
        ResponseEntity<List> issues = client.get().uri("/api/users/1/coupon-issues").retrieve().toEntity(List.class);
        assertThat(issues.getStatusCode().value()).isEqualTo(200);
        assertThat((List<Map<String, Object>>) issues.getBody())
                .anySatisfy(i -> assertThat(((Number) i.get("couponId")).longValue()).isEqualTo(couponId));

        // 없는 쿠폰 404
        ResponseEntity<Map> notFound = post("/api/coupons/999999999/issue", Map.of("userId", 1));
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
        assertThat(notFound.getBody()).containsEntry("error", "coupon_not_found");
    }

    @Test
    void 검증_실패는_400() {
        assertThat(post("/api/coupons", Map.of("name", "", "totalQuantity", 0)).getStatusCode().value())
                .isEqualTo(400);
        // VARCHAR(100) 초과 — @Size가 없으면 INSERT에서 터져 500이 된다
        assertThat(post("/api/coupons", Map.of("name", "n".repeat(101), "totalQuantity", 1)).getStatusCode().value())
                .isEqualTo(400);
        // userId 누락
        ResponseEntity<Map> created = post("/api/coupons", Map.of("name", "v", "totalQuantity", 1));
        long couponId = ((Number) created.getBody().get("id")).longValue();
        assertThat(post("/api/coupons/" + couponId + "/issue", Map.of()).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void 알림_실패는_504지만_발급은_이미_커밋되어_있다() {
        // 테스트 환경엔 mock-external(8081)이 없다 → connection refused → 504 (GlobalExceptionHandler).
        // 그런데 발급은 트랜잭션 밖 알림 호출 전에 이미 커밋됨 — 재요청이 already_issued인 것으로 증명한다.
        // 이 불일치가 E7(멱등성)·E8-5(outbox)의 동기 (journal 2026-08-26).
        ResponseEntity<Map> created = post("/api/coupons", Map.of("name", "notify", "totalQuantity", 1));
        long couponId = ((Number) created.getBody().get("id")).longValue();

        ResponseEntity<Map> res = post("/api/coupons/" + couponId + "/issue-and-notify", Map.of("userId", 42));
        assertThat(res.getStatusCode().value()).isEqualTo(504);
        assertThat(res.getBody()).containsEntry("error", "external_unreachable");

        ResponseEntity<Map> retry = post("/api/coupons/" + couponId + "/issue-and-notify", Map.of("userId", 42));
        assertThat(retry.getStatusCode().value()).isEqualTo(409);
        assertThat(retry.getBody()).containsEntry("error", "already_issued");
    }

    @Test
    void 같은_Idempotency_Key로_10회_재시도해도_발급은_1건이고_응답은_모두_동일하다() {
        // E7 (PLAN §1.2.1): per-vu-iterations 10회 재시도 시나리오의 코드 레벨 사전 검증.
        // 응답을 Map으로 역직렬화해 비교하지 않는다 — Jackson이 작은 정수를 Long/Integer로 왕복시켜
        // 실제 HTTP 바이트는 같아도 객체 비교가 어긋날 수 있다. 원본 JSON 문자열을 그대로 비교한다.
        ResponseEntity<Map> created = post("/api/coupons", Map.of("name", "idem", "totalQuantity", 5));
        long couponId = ((Number) created.getBody().get("id")).longValue();
        String idempotencyKey = "test-idem-" + System.nanoTime();

        List<ResponseEntity<String>> responses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            responses.add(client.post().uri("/api/coupons/" + couponId + "/issue")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("userId", 777))
                    .retrieve().toEntity(String.class));
        }

        ResponseEntity<String> first = responses.get(0);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        for (ResponseEntity<String> response : responses) {
            assertThat(response.getStatusCode()).isEqualTo(first.getStatusCode());
            assertThat(response.getBody()).isEqualTo(first.getBody());
        }

        ResponseEntity<List> issues = client.get().uri("/api/users/777/coupon-issues").retrieve().toEntity(List.class);
        assertThat(issues.getBody()).hasSize(1);
    }

    @Test
    void 다른_Idempotency_Key는_각각_독립적으로_처리된다() {
        ResponseEntity<Map> created = post("/api/coupons", Map.of("name", "idem-distinct", "totalQuantity", 5));
        long couponId = ((Number) created.getBody().get("id")).longValue();

        ResponseEntity<Map> a = client.post().uri("/api/coupons/" + couponId + "/issue")
                .header("Idempotency-Key", "key-a-" + System.nanoTime())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("userId", 778))
                .retrieve().toEntity(Map.class);
        ResponseEntity<Map> b = client.post().uri("/api/coupons/" + couponId + "/issue")
                .header("Idempotency-Key", "key-b-" + System.nanoTime())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("userId", 779))
                .retrieve().toEntity(Map.class);

        assertThat(a.getStatusCode().value()).isEqualTo(201);
        assertThat(b.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void Idempotency_Key_없이_보내면_기존_동작과_동일하다() {
        // 헤더 미전송 회귀 방지 — E6 이전 동작(생성_조회_발급_중복_품절_404_계약)과 동일해야 한다.
        ResponseEntity<Map> created = post("/api/coupons", Map.of("name", "no-idem", "totalQuantity", 1));
        long couponId = ((Number) created.getBody().get("id")).longValue();

        assertThat(post("/api/coupons/" + couponId + "/issue", Map.of("userId", 555)).getStatusCode().value())
                .isEqualTo(201);
        ResponseEntity<Map> dup = post("/api/coupons/" + couponId + "/issue", Map.of("userId", 555));
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
        assertThat(dup.getBody()).containsEntry("error", "already_issued");
    }

    @Test
    void 처리중인_같은_Idempotency_Key_재시도는_409_request_in_progress() {
        // 아직 처리 중(PROCESSING)인 클레임이 이미 있을 때 같은 키가 또 오는 상태(동시 재시도)를 결정적으로 재현한다.
        // 실제 동시성 하에서 예외가 던져지는 건 IdempotencyServiceTest가 검증 — 여기선 그 예외의 HTTP 매핑(409)만 본다.
        ResponseEntity<Map> created = post("/api/coupons", Map.of("name", "idem-inflight", "totalQuantity", 5));
        long couponId = ((Number) created.getBody().get("id")).longValue();

        String idempotencyKey = "inflight-" + System.nanoTime();
        redisTemplate.opsForValue().set("idempotency:" + idempotencyKey, "__PROCESSING__"); // PROCESSING_MARKER

        ResponseEntity<Map> res = client.post().uri("/api/coupons/" + couponId + "/issue")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("userId", 780))
                .retrieve().toEntity(Map.class);

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody()).containsEntry("error", "request_in_progress");
    }
}
