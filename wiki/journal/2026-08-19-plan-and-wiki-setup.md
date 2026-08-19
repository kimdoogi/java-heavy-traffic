---
title: "2026-08-19 계획 수립 & 위키 체계 구축"
date: 2026-08-19
status: done
tags: [journal, planning, setup]
related: [../../PLAN.md, ../decisions/D-001-domain-flash-sale-coupon.md, ../decisions/D-002-spring-mvc-virtual-threads-over-webflux.md, ../decisions/D-003-llm-wiki-workflow.md]
---

## 목표
- Java 21 Virtual Thread 고트래픽 실험 프로젝트의 주제·계획·기록 체계를 확정한다.

## 범위 / 하지 않는 것
- 코드는 아직 작성하지 않는다 (1주차 뼈대 생성은 다음 작업).

## 진행 기록 (시간순)
- 환경 확인: 디렉토리 비어 있음, `openjdk 21.0.1`, Docker 있음, gradle/mvn/k6 없음(→ wrapper·brew로 해결 예정), 8코어/16GB.
- 주제 후보 비교: 선착순 쿠폰 / URL 단축기 / API Aggregator / 채팅. "VT는 I/O 대기가 많을 때만 효과"라는 기준으로 **선착순 쿠폰 + 느린 외부 연동** 선택 → [D-001](../decisions/D-001-domain-flash-sale-coupon.md).
- PLAN.md v1 작성: 목표/성공 기준, 실험용 엔드포인트 설계, 발급 전략 4종(none/db-pessimistic/db-optimistic/redis), 로컬↔클라우드 매핑표, 리소스 프로파일 S/M/L, 뼈대 디렉토리, 실험 매트릭스 E1~E13, 5주 로드맵, 학습 체크리스트.
- 질문 "외부 API 장애/지연은 어떻게 테스트?" → E8 상세 설계 추가 (PLAN.md §4.6): 장애 주입 3계층(mock admin API / Toxiproxy / docker stop·pause) × 방어 단계(무방비→타임아웃→재시도→CB→벌크헤드→비동기 outbox→회복). 함정 2개 의도적으로 포함(트랜잭션 안 외부 호출로 Hikari 고갈, VT 수가 `jvm_threads_live`에 안 잡힘).
- 요청 "모든 과정을 llm-wiki처럼 작업하고 기록하는 루프 반복" → wiki/ 구조·템플릿·CLAUDE.md 생성 → [D-003](../decisions/D-003-llm-wiki-workflow.md).

## 결과
- `PLAN.md` (마스터 플랜), `CLAUDE.md` (세션 규칙), `wiki/` (index, log, templates, decisions 3건, howto 1건)

## 배운 것 / 결정
- VT 관점 핵심 통찰(가설, 실험으로 검증 예정): "플랫폼 쓰레드 200개 한도는 사실 강제 백프레셔였고, VT는 그 한도가 없어 타임아웃·벌크헤드가 선택이 아니라 필수가 된다."
- PLAN.md §8 기본값으로 진행: Gradle Groovy, Spring Data JPA, G1 기본(E10에서 ZGC 비교), 자체 mock-external 앱, 클라우드 검증은 선택.

## 남은 일 / 다음 단계
- [ ] 1주차 뼈대: coupon-api, mock-external, docker-compose(리소스 제한), Prometheus/Grafana, k6 설치, 실험용 엔드포인트, `scripts/run-experiment.sh`
- [ ] E1 계측 검증 실행 → `wiki/experiments/E1-baseline.md`
- [ ] (선택) git 커밋 정책 확정 — 작업 단위마다 커밋할지 사용자 확인
