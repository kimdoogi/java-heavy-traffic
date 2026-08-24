# E3-M-on-800

```
name=E3-M-on-800
date=2026-08-24T10:46:54+0900
git=37f56ff
profile=M cpus=1 mem=1g xmx=512m
VT=true
APP_JAVA_OPTS=-Xmx512m -XX:+UseG1GC
POOL_SIZE=20(default)
ISSUE_STRATEGY=default
# ISSUE_STRATEGY는 3주차 전략 구현 전까지 동작에 영향 없음 (property로만 소비)
extra_env=
scenario=load/30-cpu-bound.js
k6_extra=
MAX_RPS=800
mock_fault={"mode":"normal","delayMs":300,"jitterMs":0,"failRate":0.0,"status":500,"hangSeconds":300,"flapPeriodSeconds":10}
effective_virtual=true
```

| name | profile | VT | pool | scenario | rps | p50 ms | p95 ms | p99 ms | failed | dropped | maxVUs | app cpu peak | app mem |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E3-M-on-800 | M | true | 20(default) | 30-cpu-bound.js | 460.9 | 2.7 | 1740.5 | 2441.0 | 0% | 1997 | 1442 | 102.5% | 444.6MiB / 1GiB |
