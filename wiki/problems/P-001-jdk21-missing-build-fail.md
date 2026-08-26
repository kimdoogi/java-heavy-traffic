---
title: "P-001 로컬 JDK 21 소실로 빌드 실패"
date: 2026-08-24
status: solved
tags: [problem, build, gradle]
related: [../journal/2026-08-24-E3-cpu-bound.md]
---

## 증상
- `scripts/build.sh` 실패:
```
Cannot find a Java installation on your machine (Mac OS X 26.6 aarch64) matching: {languageVersion=21, ...}.
Toolchain download repositories have not been configured.
```

## 재현 / 확인
- `/usr/libexec/java_home -V` → corretto-17.0.14만 존재. brew에는 openjdk(23.0.2)·openjdk@17만.
- 8/20(E2)까지는 빌드됐으므로 그 사이 JDK 21이 제거된 것으로 보임(brew 정리 추정 — 가설, 미확인).

## 원인
- Gradle toolchain(languageVersion=21)이 요구하는 JDK 21이 로컬에 없고, toolchain 다운로드 저장소도 미설정.

## 해결
- `settings.gradle`에 foojay resolver 추가 → JDK 21 자동 다운로드:
```gradle
plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
}
```
- 빌드 성공 확인 (`scripts/build.sh` → jar + 이미지).

## 재발 방지
- 저장소에 커밋했으므로 popogustn 머신에 JDK 21이 없어도 자동 해결됨. 로컬 JDK 설치 불필요.

## 추가 발생 (2026-08-26) — 같은 "로컬 툴 소실" 패턴
- 이번엔 `gh` CLI 소실 (`command not found`, `~/.config/gh`도 없음). JDK 21·k6에 이어 세 번째.
- 조치: `brew install gh` 재설치. gh 인증은 없었지만 https push가 되는 걸로 보아 keychain에 git 자격증명이 살아 있음 → `git credential fill`에서 토큰을 꺼내 `GH_TOKEN`으로 넘겨 `gh pr create` 성공 (토큰 값은 출력하지 않음). 영구 인증이 필요하면 `gh auth login` 직접 실행할 것.
- 원인은 여전히 가설(brew 정리 추정). 세 번째 발생이므로 다음에 또 사라지면 원인 추적을 우선할 것.
