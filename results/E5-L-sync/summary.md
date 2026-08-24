# E5-L-sync

```
name=E5-L-sync
date=2026-08-24T11:35:31+0900
git=3555a7e
profile=L cpus=2 mem=2g xmx=1g
VT=true
APP_JAVA_OPTS=-Xmx1g -XX:+UseG1GC -Djdk.tracePinnedThreads=full
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
| E5-L-sync | L | true | 20(default) | 22-pin.js | 35.4 | 25434.0 | 54223.8 | 54298.1 | 0% | 10898 | 2000 | 44.7% | 446.8MiB / 2GiB |
