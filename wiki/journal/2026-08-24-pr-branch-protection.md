---
title: "2026-08-24 main 보호 — PR + 상대 리뷰 필수화"
date: 2026-08-24
status: done
tags: [journal, collaboration, git]
related: [../decisions/D-005-two-person-track-split.md, ../howto/git.md]
---

## 목표
- main 직접 push를 막고, PR + 상대 승인 1명 없이는 머지 불가하게 구성.

## 진행 기록 (시간순)
- CLAUDE.md 싱크 절을 "브랜치/PR" 절로 교체, howto/git.md에 PR 정책·명령 추가.
- 규칙 문서를 마지막 직접 push로 올린 뒤 branch protection 적용(순서 중요 — 먼저 걸면 문서 push부터 막힘).
- `gh api PUT /repos/kimdoogi/java-heavy-traffic/branches/main/protection` — required_pull_request_reviews(승인 1), enforce_admins=true, force push/삭제 금지.

## 결과
- main 보호 활성. 이후 모든 변경은 `a/<슬러그>`·`b/<슬러그>` 브랜치 → PR → 상대 승인 → merge.

## 배운 것 / 결정
- 2인 팀이라 승인 1명 = 자동으로 "상대 리뷰"(본인 PR 본인 승인 불가).
- 트레이드오프: 위키·results만 바꾸는 커밋도 상대 승인이 필요해짐 — 기록 속도가 리뷰에 묶임. 부담되면 경로 예외는 불가(브랜치 단위 보호)이므로 승인 0으로 낮추는 것만 가능. 일단 전부 리뷰로 시작.

## 남은 일 / 다음 단계
- [ ] popogustn 초대 수락 확인(수락 전엔 리뷰어 지정 실패할 수 있음)
