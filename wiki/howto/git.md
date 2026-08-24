---
title: "Git 사용 규칙"
date: 2026-08-19
status: live
tags: [howto, git]
related: [../../CLAUDE.md]
---

## 원격
- `origin` = https://github.com/kimdoogi/java-heavy-traffic.git (브랜치 `main`)
- 이 저장소 로컬 identity: `kimdoogi <dugiz94@gmail.com>` (전역 설정은 회사 메일이라 레포 단위로 덮어씀. 변경: `git config user.email ...`)

## 커밋 정책
- **작업 단위(journal 1건)마다 1커밋 이상**. 실험은 "코드/설정 변경 커밋" + "결과 기록 커밋"으로 나눠도 좋다.
- 메시지: `<type>: <요약>` (type: feat / fix / exp / docs / chore / infra). 본문에 관련 위키 페이지 경로를 적는다.
  - 예: `exp: E2 I/O bound VT vs platform 결과 기록 (wiki/experiments/E2-io-bound.md)`
- 문제를 해결한 커밋은 본문에 `P-NNN` 번호를 남겨 카드 ↔ 커밋을 연결한다.
- 세션 종료 시 `git push`. 커밋 없이 세션을 끝내지 않는다 (위키 갱신도 커밋 대상).

## PR 정책 (2026-08-24부터, 2인 협업)
- main은 branch protection: **PR 필수 + 승인 1명 + 관리자 포함 강제**. 직접 push는 거부된다.
- 브랜치 이름: `a/<슬러그>`(kimdoogi) · `b/<슬러그>`(popogustn). journal 1건 = 브랜치 1개 = PR 1개.
- 본인 PR 본인 승인 불가(GitHub 기본) → 항상 상대가 리뷰. 리뷰 요청은 PR 생성 시 자동/수동 지정.
- 머지 후 브랜치 삭제. 로컬은 `git switch main && git pull --rebase`.

## 자주 쓰는 명령
```bash
git switch -c a/<슬러그>                       # 작업 시작 (A 기준)
git add -A && git commit -m "docs: ..." && git push -u origin HEAD
gh pr create --fill --reviewer popogustn      # PR 생성 + 리뷰 요청
gh pr status                                  # 내 PR / 리뷰 대기 확인
gh pr merge --merge --delete-branch           # 승인 후 머지
git log --oneline --graph -20
```
