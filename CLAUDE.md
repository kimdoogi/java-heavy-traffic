# java-heavy-traffic — 작업 규칙

## 프로젝트 한 줄 요약
Java 21 Virtual Thread 기반 선착순 쿠폰 서비스를 리소스 제한(CPU/메모리) 하에서 부하 테스트하며 한계와 병목을 수치로 기록하는 학습/포트폴리오 프로젝트.
- 마스터 플랜: `PLAN.md` (실험 E1~E13, 뼈대, 로컬↔클라우드 매핑, 로드맵)
- 지식·기록 위키: `wiki/` (아래 워크플로우의 중심)

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
