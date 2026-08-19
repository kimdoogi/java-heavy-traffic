---
title: "D-002 Spring MVC + Virtual Thread (WebFlux 대신)"
date: 2026-08-19
status: accepted
tags: [decision, stack]
related: [../../PLAN.md]
---

## 맥락
- 고동시성 I/O 서비스의 전통적 선택지는 리액티브(WebFlux). Java 21부터는 블로킹 코드 그대로 두고 VT만 켜는 선택지가 생겼다.

## 선택지
1. **Spring MVC + `spring.threads.virtual.enabled=true`** — 장점: 코드 단순(블로킹 스타일), 한 줄 토글로 플랫폼 vs VT **동일 코드 비교** 가능, JPA/JDBC 등 블로킹 라이브러리 그대로 사용 / 단점: pinning 등 VT 특유의 함정 존재(→ 오히려 실험 대상)
2. Spring WebFlux — 장점: 검증된 고동시성 / 단점: 코드 복잡, R2DBC 등 스택 전환 필요, "VT 학습"이라는 목적과 어긋남

## 결정
- 1번.

## 이유
- 실험의 핵심 변수가 "같은 코드에서 쓰레드 모델만 바꿨을 때"이므로 MVC가 유일하게 그 비교를 깨끗하게 만든다.

## 결과 / 영향
- 프로파일 `platform`(Tomcat max-threads 200) / `virtual` 두 개로 모든 실험을 2회씩 돌린다.
- HTTP 클라이언트도 동기 `RestClient`를 쓴다 (WebClient 사용 안 함).
