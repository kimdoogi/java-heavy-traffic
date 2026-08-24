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
