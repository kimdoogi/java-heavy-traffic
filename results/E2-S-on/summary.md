# E2-S-on

```
name=E2-S-on
date=2026-08-20T21:55:42+0900
git=6953f61
profile=S cpus=0.5 mem=512m xmx=256m
VT=true
APP_JAVA_OPTS=-Xmx256m -XX:+UseG1GC
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
| E2-S-on | S | true | 20(default) | 20-io-sleep.js | 576.8 | 301.4 | 406.1 | 60000.7 | 2.76% | 102693 | 3000 | 50.7% | 507.7MiB / 512MiB |
