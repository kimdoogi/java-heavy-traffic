# E5-L-lock

```
name=E5-L-lock
date=2026-08-24T11:38:42+0900
git=3555a7e
profile=L cpus=2 mem=2g xmx=1g
VT=true
APP_JAVA_OPTS=-Xmx1g -XX:+UseG1GC -Djdk.tracePinnedThreads=full
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
| E5-L-lock | L | true | 20(default) | 22-pin.js | 1155.6 | 51.4 | 270.1 | 1588.8 | 0% | 6750 | 2000 | 278.9% | 522.6MiB / 2GiB |
