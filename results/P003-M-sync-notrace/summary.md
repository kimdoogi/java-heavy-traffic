# P003-M-sync-notrace

```
name=P003-M-sync-notrace
date=2026-08-25T17:56:11+0900
git=acd3073-dirty
profile=M cpus=1 mem=1g xmx=512m
VT=true
APP_JAVA_OPTS=-Xmx512m -XX:+UseG1GC
POOL_SIZE=20(default)
ISSUE_STRATEGY=default
# ISSUE_STRATEGY는 3주차 전략 구현 전까지 동작에 영향 없음 (property로만 소비)
extra_env=PIN_MODE=sync
scenario=load/22-pin.js
k6_extra=
PIN_MODE=sync
mock_fault={"mode":"normal","delayMs":300,"jitterMs":0,"failRate":0.0,"status":500,"hangSeconds":300,"flapPeriodSeconds":10}
effective_virtual=true
```

| name | profile | VT | pool | scenario | rps | p50 ms | p95 ms | p99 ms | failed | dropped | maxVUs | app cpu peak | app mem |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| P003-M-sync-notrace | M | true | 20(default) | 22-pin.js | 35.5 | 25580.5 | 53981.9 | 54006.6 | 0% | 10887 | 2000 | 31.1% | 494.6MiB / 1GiB |
