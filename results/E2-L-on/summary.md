# E2-L-on

```
name=E2-L-on
date=2026-08-20T22:10:11+0900
git=6953f61
profile=L cpus=2 mem=2g xmx=1g
VT=true
APP_JAVA_OPTS=-Xmx1g -XX:+UseG1GC
POOL_SIZE=20(default)
ISSUE_STRATEGY=default
# ISSUE_STRATEGY는 3주차 전략 구현 전까지 동작에 영향 없음 (property로만 소비)
extra_env=
scenario=load/20-io-sleep.js
k6_extra=
SLEEP_MS=300
MAX_RPS=2000
MAX_VUS=3000
STEP_DUR=30s
mock_fault={"mode":"normal","delayMs":300,"jitterMs":0,"failRate":0.0,"status":500,"hangSeconds":300,"flapPeriodSeconds":10}
effective_virtual=true
```

| name | profile | VT | pool | scenario | rps | p50 ms | p95 ms | p99 ms | failed | dropped | maxVUs | app cpu peak | app mem |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E2-L-on | L | true | 20(default) | 20-io-sleep.js | 1169.9 | 301.2 | 303.1 | 306.8 | 0% | 554 | 632 | 49.4% | 398.8MiB / 2GiB |
