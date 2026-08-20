package com.example.coupon.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 동기 RestClient (WebClient 아님 — D-002). 블로킹 호출이 VT 위에서 어떻게 동작하는지 보는 것이 목적.
 * JDK HttpClient를 기반으로 connect/read 타임아웃을 설정한다.
 */
@Configuration
@EnableConfigurationProperties(ExternalProperties.class)
public class RestClientConfig {

    @Bean
    public RestClient externalRestClient(ExternalProperties props) {
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1);
        if (props.connectTimeoutMs() > 0) {              // 0 = 무제한 (E8-0), Duration.ZERO는 IAE
            httpClientBuilder.connectTimeout(Duration.ofMillis(props.connectTimeoutMs()));
        }
        HttpClient httpClient = httpClientBuilder.build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        if (props.readTimeoutMs() > 0) {
            factory.setReadTimeout(Duration.ofMillis(props.readTimeoutMs()));
        }
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
