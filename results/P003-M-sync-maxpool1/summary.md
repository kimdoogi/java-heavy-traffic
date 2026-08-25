# P003-M-sync-maxpool1

```
name=P003-M-sync-maxpool1
date=2026-08-25T18:03:29+0900
git=acd3073-dirty
profile=M cpus=1 mem=1g xmx=512m
VT=true
APP_JAVA_OPTS=-Xmx512m -XX:+UseG1GC -Djdk.virtualThreadScheduler.maxPoolSize=1
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
| P003-M-sync-maxpool1 | M | true | 20(default) | 22-pin.js | 23.1 | 56823.2 | 60000.6 | 60000.8 | 47.97% | 12538 | 2000 | 20.3% | 378.5MiB / 1GiB |
