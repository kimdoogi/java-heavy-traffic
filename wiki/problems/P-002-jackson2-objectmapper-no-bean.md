---
title: "P-002 Spring Boot 4/Spring 7에서 Jackson 2 ObjectMapper DI가 NoSuchBeanDefinitionException"
date: 2026-08-27
status: solved
tags: [problem, spring-boot-4, jackson]
severity: medium
related: [../journal/2026-08-27-E7-idempotency.md, ../decisions/D-004-spring-boot-4.md]
---

## 증상
`IdempotencyService` 생성자에 `com.fasterxml.jackson.databind.ObjectMapper`(Jackson 2)를 주입받게 했더니
전체 Spring 컨텍스트 로딩이 깨지고, 이 컨텍스트를 공유하는 `@SpringBootTest` 20개 전부가 연쇄로 실패했다
(이 서비스와 무관한 `RedisIssueRecoveryTest`·`IssueStrategyConcurrencyTest`까지 포함).

```
Caused by: org.springframework.beans.factory.NoSuchBeanDefinitionException:
No qualifying bean of type 'com.fasterxml.jackson.databind.ObjectMapper' available:
expected at least 1 bean which qualifies as autowire candidate. Dependency annotations: {}
```

## 재현
- `./gradlew :coupon-api:test` (Spring Boot 4.0.x, `spring-boot-starter-webmvc`)
- 아무 빈 생성자에나 `com.fasterxml.jackson.databind.ObjectMapper` 파라미터를 추가하면 재현.

## 원인 분석
- 처음 가설(틀림): "`spring-boot-starter-webmvc`가 `spring-boot-starter-web`과 달라서 Jackson 오토컨피그가 안 붙었다" — 추측이었고 확인 없이 주석에 썼다가 advisor 리뷰에서 지적받음.
- 검증: `./gradlew :coupon-api:dependencies --configuration runtimeClasspath` 로 실제 클래스패스 확인.
  ```
  tools.jackson.core:jackson-databind:3.1.4        # Jackson 3 — Spring이 기본 빈으로 등록하는 타입
  com.fasterxml.jackson.core:jackson-databind:2.21.4  # Jackson 2 — 클래스패스엔 있지만 대응 빈 없음
  ```
- **확정된 원인**: Spring Boot 4 / Spring Framework 7은 Jackson 3(`tools.jackson.databind.ObjectMapper`, groupId가 `tools.jackson`으로 바뀐 신버전)을 기본 JSON 빈으로 등록한다. Jackson 2(`com.fasterxml.jackson.databind.ObjectMapper`)는 다른 라이브러리(Testcontainers 등 아직 Jackson 3로 안 옮긴 의존성)가 끌고 와서 클래스패스엔 있지만, Spring이 그 타입으로 빈을 등록해주지 않는다 — 두 Jackson 메이저 버전이 동시에 클래스패스에 있고 "기본 빈 = Jackson 3"라는 걸 모르면 바로 이 함정에 걸린다.

## 해결
- `IdempotencyService`가 DI로 `ObjectMapper`를 받지 않고 `private final ObjectMapper objectMapper = new ObjectMapper();` 로 직접 소유하게 변경 (Jackson 2 import 유지, 빈 룩업 자체를 안 함).
- 이 서비스가 직렬화하는 대상이 `{status, body}` 뿐이고 body는 String/Long/boolean 값의 Map이라 날짜·커스텀 모듈이 필요 없어 앱 전역 설정과 분리돼도 무해함 — 우회가 아니라 오히려 적절한 스코프.
- 해결 확인: `./gradlew :coupon-api:test` → 20 tests, 0 failures.

## 재발 방지 / 교훈
- Spring Boot 4/Spring 7 프로젝트에서 Jackson 관련 빈을 주입받을 땐 **어느 Jackson 메이저 버전을 기본 빈으로 쓰는지 먼저 확인**한다(`./gradlew :module:dependencies` 로 `tools.jackson` vs `com.fasterxml.jackson` 동시 존재 여부 확인). `com.fasterxml.jackson.databind.ObjectMapper` 타입으로 그냥 `@Autowired`/생성자 주입을 시도하면 컴파일은 통과하고 **런타임(컨텍스트 로딩)에만 깨진다** — 컴파일 타임엔 안 잡힌다.
- kimdoogi(A) 트랙 코드에도 영향 가능 — 앞으로 Jackson 타입을 주입받는 빈을 추가할 때 이 함정을 먼저 고려할 것.
- 원인 분석 시 "그럴듯한 추측을 검증 없이 기록"하지 않는다 — 처음 쓴 주석이 틀렸고, `./gradlew :coupon-api:dependencies` 한 번으로 정확한 원인이 나왔다. 확실치 않으면 명령으로 확인부터.

## 참고
- [journal 2026-08-27-E7-idempotency.md](../journal/2026-08-27-E7-idempotency.md)
- [D-004 Spring Boot 4.0.x 채택](../decisions/D-004-spring-boot-4.md)
