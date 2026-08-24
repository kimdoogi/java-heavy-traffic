# E5-M-sync

```
name=E5-M-sync
date=2026-08-24T11:29:21+0900
git=3555a7e
profile=M cpus=1 mem=1g xmx=512m
VT=true
APP_JAVA_OPTS=-Xmx512m -XX:+UseG1GC -Djdk.tracePinnedThreads=full
POOL_SIZE=20(default)
ISSUE_STRATEGY=default
# ISSUE_STRATEGY는 3주차 전략 구현 전까지 동작에 영향 없음 (property로만 소비)
extra_env=PIN_MODE=sync
scenario=load/22-pin.js
k6_extra=
mock_fault={"mode":"normal","delayMs":300,"jitterMs":0,"failRate":0.0,"status":500,"hangSeconds":300,"flapPeriodSeconds":10}
effective_virtual=true
```

| name | profile | VT | pool | scenario | rps | p50 ms | p95 ms | p99 ms | failed | dropped | maxVUs | app cpu peak | app mem |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E5-M-sync | M | true | 20(default) | 22-pin.js | 35.3 | 25833.4 | 54269.8 | 54319.8 | 0% | 10917 | 2000 | 38.7% | 438.9MiB / 1GiB |
