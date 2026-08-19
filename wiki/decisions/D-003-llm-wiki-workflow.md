---
title: "D-003 LLM-wiki 방식의 작업·기록 루프"
date: 2026-08-19
status: accepted
tags: [decision, process]
related: [../../CLAUDE.md, ../howto/wiki-workflow.md]
---

## 맥락
- 사용자가 "프로젝트의 모든 과정을 하나하나 담아, 나중에 어떤 문제가 생겼고 어떻게 해결했는지 확인할 수 있게" 요청.
- 세션이 바뀌어도(컨텍스트가 사라져도) 기록만으로 상태·이력을 복원할 수 있어야 한다.

## 선택지
1. **위키형 마크다운 저장소 (`wiki/`) + 매 세션 규칙(`CLAUDE.md`)** — 장점: 페이지 단위로 문제/결정/개념/실험이 누적·연결됨, 검색·Obsidian 호환, git으로 이력 / 단점: 기록 규율 필요(→ CLAUDE.md로 강제)
2. 단일 CHANGELOG/README — 장점: 단순 / 단점: 시간순 나열만 남고 "문제→해결" 연결이 약함
3. 외부 도구(Notion 등) — 장점: UI / 단점: 코드와 분리, LLM이 읽고 쓰기 번거로움

## 결정
- 1번. 구조: `index.md`(목차·상태·다음 번호), `log.md`(append-only 시간순), `journal/`(작업 단위), `problems/`(P-NNN 문제→해결 카드), `decisions/`(D-NNN ADR), `experiments/`(E<N> 결과), `concepts/`(학습 개념), `howto/`(런북), `_templates/`.

## 이유
- "문제 카드"를 독립 페이지로 두면 나중에 "어떤 문제가 있었나"를 한 목록으로 본다.
- index/log를 먼저 읽는 규칙으로 세션 간 연속성을 보장한다.

## 결과 / 영향
- 모든 세션은 CLAUDE.md의 루프(읽기 → journal 생성 → 즉시 기록 → 종료 시 log/index 갱신)를 따른다.
- git 커밋을 작업 단위마다 남기면 이력이 완성된다 (사용자 승인 후 적용).
