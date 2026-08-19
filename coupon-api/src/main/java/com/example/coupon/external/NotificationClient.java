package com.example.coupon.external;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** mock-external 의 /notify 를 동기 호출. 지연/실패는 쿼리 파라미터 또는 mock 서버의 전역 fault 설정으로 주입된다. */
@Component
public class NotificationClient {

    private final RestClient restClient;

    public NotificationClient(RestClient externalRestClient) {
        this.restClient = externalRestClient;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> notify(long userId, Integer delayMs, Double failRate) {
        return restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/notify").queryParam("userId", userId);
                    if (delayMs != null) uriBuilder.queryParam("delayMs", delayMs);
                    if (failRate != null) uriBuilder.queryParam("failRate", failRate);
                    return uriBuilder.build();
                })
                .retrieve()
                .body(Map.class);
    }
}
