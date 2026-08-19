---
title: "위키 사용법 (사람용 요약)"
date: 2026-08-19
status: live
tags: [howto, process]
related: [../../CLAUDE.md, ../decisions/D-003-llm-wiki-workflow.md]
---

## 어디를 보면 되나
| 궁금한 것 | 보는 곳 |
|---|---|
| 지금 어디까지 했나 | [index.md](../index.md) "현재 상태" → [log.md](../log.md) 마지막 줄들 |
| 어떤 문제가 있었고 어떻게 풀었나 | [index.md](../index.md) Problems 목록 → `problems/P-NNN-*.md` |
| 왜 이렇게 설계했나 | `decisions/D-NNN-*.md` |
| 실험 결과 수치 | `experiments/E<N>-*.md` (+ raw는 `../results/`) |
| 개념 정리 / 면접 답변 | `concepts/*.md` |
| 실행 방법 | `howto/*.md` |
| 특정 날짜에 뭘 했나 | `journal/YYYY-MM-DD-*.md` |

## 루프 (CLAUDE.md와 동일)
읽기(index → log → 진행 중 journal) → journal 생성 → 작업하며 즉시 기록(problem/decision/concept/experiment) → 종료 시 journal 마무리 → log 한 줄 → index 갱신 → (승인 시) 커밋

## 페이지 만들 때
- `_templates/`에서 복사, frontmatter 채우기, 번호는 index의 "다음 번호" 사용 후 index에서 번호 올리기.
- 파일명: `P-001-hikari-pool-exhausted.md`, `D-004-gc-choice.md`, `E2-io-bound-vt-vs-platform.md`, `concepts/virtual-thread-pinning.md`, `journal/2026-08-20-skeleton.md`

## 도구
- 폴더를 Obsidian vault로 열면 그래프/검색 가능 (상대경로 링크라 GitHub에서도 렌더링됨).
- 검색: `grep -rn "키워드" wiki/`
