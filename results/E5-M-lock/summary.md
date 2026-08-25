# E5-M-lock

```
name=E5-M-lock
date=2026-08-24T11:32:36+0900
git=3555a7e
profile=M cpus=1 mem=1g xmx=512m
VT=true
APP_JAVA_OPTS=-Xmx512m -XX:+UseG1GC -Djdk.tracePinnedThreads=full
POOL_SIZE=20(default)
ISSUE_STRATEGY=default
# ISSUE_STRATEGY는 3주차 전략 구현 전까지 동작에 영향 없음 (property로만 소비)
extra_env=PIN_MODE=lock
scenario=load/22-pin.js
k6_extra=
MAX_RPS=2000
mock_fault={"mode":"normal","delayMs":300,"jitterMs":0,"failRate":0.0,"status":500,"hangSeconds":300,"flapPeriodSeconds":10}
effective_virtual=true
```

| name | profile | VT | pool | scenario | rps | p50 ms | p95 ms | p99 ms | failed | dropped | maxVUs | app cpu peak | app mem |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E5-M-lock | M | true | 20(default) | 22-pin.js | 1048.4 | 51.9 | 1738.7 | 2749.2 | 0% | 22637 | 2000 | 142.8% | 839.5MiB / 1GiB |
