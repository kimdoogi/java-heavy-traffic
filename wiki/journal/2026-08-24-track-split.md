---
title: "2026-08-24 2인 협업 트랙 확정 및 규칙 문서화"
date: 2026-08-24
status: done
tags: [journal, collaboration]
related: [../decisions/D-005-two-person-track-split.md]
---

## 목표
- 8/21 논의한 2인 트랙 분할의 담당자를 확정하고 CLAUDE.md에 협업 규칙을 박는다.

## 범위 / 하지 않는 것
- 규칙 문서화만. 코드 변경 없음. popogustn 초대 수락 여부 확인은 하지 않음(수락 후 본인이 규칙 읽으면 됨).

## 진행 기록 (시간순)
- 8/21 세션(위키 미기록)에서 트랙 A(런타임·자원)/B(도메인·장애) 분할안과 번호 홀짝 방식까지 논의, 담당자 미정으로 종료.
- 오늘 담당 확정: **A = kimdoogi, B = popogustn**.
- [D-005](../decisions/D-005-two-person-track-split.md) 작성, CLAUDE.md에 "2인 협업 규칙" 절 추가(담당 표, 경로 소유권, 번호 홀짝, 계약, 싱크).
- index.md "다음 번호"를 트랙별(A 홀수/B 짝수)로 변경.

## 결과
- CLAUDE.md 협업 규칙 절, D-005, index/log 갱신.

## 배운 것 / 결정
- → [D-005](../decisions/D-005-two-person-track-split.md)

## 남은 일 / 다음 단계
- [ ] popogustn 초대 수락 확인 후 CLAUDE.md·D-005 읽고 이견 있으면 D-005 갱신
- [ ] A(kimdoogi): E3(CPU bound) 시작
- [ ] B(popogustn): 쿠폰 도메인 구현 착수 (PLAN.md 3주차)
