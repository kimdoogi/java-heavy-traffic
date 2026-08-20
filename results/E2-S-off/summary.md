# E2-S-off

```
name=E2-S-off
date=2026-08-20T21:59:32+0900
git=6953f61
profile=S cpus=0.5 mem=512m xmx=256m
VT=false
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
effective_virtual=false
```

| name | profile | VT | pool | scenario | rps | p50 ms | p95 ms | p99 ms | failed | dropped | maxVUs | app cpu peak | app mem |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E2-S-off | S | false | 20(default) | 20-io-sleep.js | 567.6 | 4514.7 | 5177.8 | 5675.8 | 0% | 106754 | 3000 | 50.4% | 447.7MiB / 512MiB |
