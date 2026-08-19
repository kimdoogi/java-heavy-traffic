---
title: "D-004 Spring Boot 4.0.x 채택 (계획의 3.3.x 대신)"
date: 2026-08-19
status: accepted
tags: [decision, stack]
related: [../journal/2026-08-19-skeleton.md, ../../PLAN.md]
---

## 맥락
- PLAN.md는 Spring Boot 3.3.x를 가정했으나, 2026-08 시점 start.spring.io는 **4.1.0 / 4.0.7만 제공** (3.x는 OSS 지원 종료).
- 수동으로 3.5.x를 쓸 수도 있지만 EOL 버전으로 포트폴리오를 만드는 것은 마이너스.

## 선택지
1. **Boot 4.0.7 (4.0 안정 패치 라인)** — 장점: 현행, Java 21 지원, VT 토글 동일(`spring.threads.virtual.enabled`) / 단점: 서드파티(Resilience4j 등) 호환 확인 필요
2. Boot 4.1.0 (최신 GA) — 장점: 최신 / 단점: 패치 적게 쌓임
3. Boot 3.5.x 수동 지정 — 장점: 익숙 / 단점: EOL

## 결정
- 1번. Gradle 9.5.1 (wrapper), Java 21 toolchain.

## 이유
- 실험의 본질(VT, 풀, 장애 대응)은 Boot 버전과 무관. 현행 버전이 포트폴리오 가치가 높다.

## 결과 / 영향
- 스타터명 변화 주의: `spring-boot-starter-webmvc`, 테스트 스타터 분리(`-webmvc-test`, `-data-jpa-test`), `spring-boot-starter-flyway`, Testcontainers 2.x(`testcontainers-postgresql`).
- Resilience4j(4주차 E8)는 `cloud-resilience4j`(Spring Cloud CircuitBreaker) 또는 resilience4j core를 직접 사용 — 그때 결정.
- PLAN.md의 "3.3.x" 표기는 이 ADR로 대체.
