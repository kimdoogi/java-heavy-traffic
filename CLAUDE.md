# java-heavy-traffic — 작업 규칙

## 프로젝트 한 줄 요약
Java 21 Virtual Thread 기반 선착순 쿠폰 서비스를 리소스 제한(CPU/메모리) 하에서 부하 테스트하며 한계와 병목을 수치로 기록하는 학습/포트폴리오 프로젝트.
- 마스터 플랜: `PLAN.md` (실험 E1~E13, 뼈대, 로컬↔클라우드 매핑, 로드맵)
- 지식·기록 위키: `wiki/` (아래 워크플로우의 중심)

## 2인 협업 규칙 (D-005, 2026-08-24)

### 담당 트랙
| 트랙 | 담당 | 범위 |
|---|---|---|
| **A 런타임·자원** | **kimdoogi** | E3·E4·E5·E10·E11·E12 — VT 내부, 커넥션 풀, GC, soak, 스케일아웃 |
| **B 도메인·장애** | **popogustn** | 쿠폰 도메인 구현 + E6·E7·E8·E9·E13 — 동시성 제어, 멱등성, Resilience, 백프레셔 |

### 경로 소유권 (상대 소유 경로는 합의 없이 수정 금지)
- **A**: `monitoring/`, `scripts/`, `load/` 기존 시나리오(00~30·60), `coupon-api/…/experiment/`
- **B**: `coupon-api/…/coupon` 도메인 신규 패키지, `mock-external/`, `load/` 쿠폰·장애 신규 시나리오
- **공유(수정 전 상대 합의)**: `docker-compose.yml`, `build.gradle`, `settings.gradle`, `PLAN.md`, `CLAUDE.md`, `coupon-api/…/common/`·`config/`
- wiki: 각자 자기 트랙의 journal/experiment/problem/decision만 생성.

### 위키 번호 (P-/D- 공통)
- **A=홀수, B=짝수**. 재사용 금지. 다음 번호는 `wiki/index.md`의 트랙별 항목을 쓴다.

### index.md / log.md 충돌
- `log.md`: append-only. 머지 충돌 시 양쪽 줄 다 살리고 날짜순 정렬.
- `index.md`: 자기 항목 추가만. "다음 번호"는 자기 트랙 것만 갱신.

### API 계약
- 쿠폰 API 스펙(경로·요청·응답·에러)은 `PLAN.md`가 기준. B가 바꾸면 PLAN.md 먼저 수정하고 커밋 제목에 `contract:` 접두 → A는 k6 시나리오를 그에 맞춘다.

### 브랜치 / PR (2026-08-24부터)
- **main 직접 push 금지** (branch protection: PR 필수, 관리자 포함). 승인 요구는 2026-08-25 해제 — 승인 없이 merge 가능하나 상대 리뷰는 권장.
- 브랜치: `a/<슬러그>`(kimdoogi) · `b/<슬러그>`(popogustn). 작업 단위(journal)당 브랜치 1개.
- 흐름: 브랜치 커밋 → push → `gh pr create` → (리뷰는 선택) → merge(squash 아님, merge commit) → 브랜치 삭제.
- 트랙 간 의존: E4(A)는 B의 쿠폰 도메인(`/coupons/{id}`) 완성 후 실행 가능.

## 위키 우선 워크플로우 (모든 세션 필수)
"작업 → 기록 → 반복". 코드가 진행돼도 기록이 끊기면 안 된다. 나중에 "어떤 문제가 있었고 어떻게 해결했는지"를 위키만 보고 재구성할 수 있어야 한다.

1. **세션 시작**: `wiki/index.md` → `wiki/log.md`(최근 항목) → 진행 중(`status: in-progress`) journal 순으로 읽고 현재 상태를 파악한 뒤 작업한다.
2. **작업 시작**: `wiki/journal/YYYY-MM-DD-<슬러그>.md`를 `wiki/_templates/journal.md`로 생성하고 목표·범위를 먼저 적는다.
3. **작업 중** (발생 즉시 기록, 나중에 몰아서 쓰지 않는다):
   - 에러/예상 밖 동작/막힘 → `wiki/problems/P-NNN-<슬러그>.md` 생성. 증상·재현·실제 출력을 먼저 적고, 해결되면 원인·해결·재발 방지를 채운다. 미해결이면 `status: open`으로 남긴다.
   - 설계/기술 선택 → `wiki/decisions/D-NNN-<슬러그>.md` (ADR: 맥락, 선택지, 결정, 이유, 결과).
   - 새로 이해한 개념 → `wiki/concepts/<슬러그>.md` (면접에서 답할 수 있는 수준으로).
   - 실험 수행 → `wiki/experiments/E<N>-<슬러그>.md` (가설/설정/결과/해석). raw 데이터(k6 summary, docker stats)는 `results/`에 두고 링크.
4. **작업 종료**: journal에 한 일·결과·남은 일 완성 → `wiki/log.md`에 한 줄 append → `wiki/index.md` 갱신(새 페이지 등록, 상태·다음 번호 갱신).
5. **커밋/푸시**: 작업 단위(journal)마다 커밋하고 세션 종료 시 `git push` (원격: github.com/kimdoogi/java-heavy-traffic, 2026-08-19 연결). 규칙은 `wiki/howto/git.md`. 문제 해결 커밋은 본문에 `P-NNN`을 남긴다.

## 기록 규칙
- 모든 페이지 상단에 frontmatter(`title/date/status/tags/related`) 필수. 템플릿은 `wiki/_templates/`.
- 링크는 상대경로 마크다운 링크. problem ↔ journal ↔ concept ↔ experiment ↔ decision을 적극 연결한다.
- `P-`/`D-` 번호는 `wiki/index.md`의 "다음 번호"를 쓰고 재사용하지 않는다.
- 사실만 기록: 실행한 명령, 실제 출력, 측정 수치. 추측은 "가설"로 표시하고 검증 후 갱신.
- 실패도 기록: 안 된 시도, 버린 접근과 그 이유. 이게 가장 가치 있는 기록이다.
- 한국어로 작성. 코드·명령·지표명·에러 메시지는 원문 유지.

## 개발 규칙 (요약)
- Java 21, Spring Boot 4.0.x (D-004), Gradle wrapper(전역 설치 없음). 실행/빌드/테스트 방법은 `wiki/howto/`에 둔다.
- 실험은 `scripts/run-experiment.sh`로만 실행해 재현 가능하게 한다. 수동 실행했다면 명령을 journal에 그대로 남긴다.
- 리소스 제한은 docker compose에서만 건다(프로파일 S/M/L, `PLAN.md` §2.1).
