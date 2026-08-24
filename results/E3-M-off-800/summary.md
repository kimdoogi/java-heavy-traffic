# E3-M-off-800

```
name=E3-M-off-800
date=2026-08-24T10:49:38+0900
git=37f56ff
profile=M cpus=1 mem=1g xmx=512m
VT=false
APP_JAVA_OPTS=-Xmx512m -XX:+UseG1GC
POOL_SIZE=20(default)
ISSUE_STRATEGY=default
# ISSUE_STRATEGY는 3주차 전략 구현 전까지 동작에 영향 없음 (property로만 소비)
extra_env=
scenario=load/30-cpu-bound.js
k6_extra=
MAX_RPS=800
mock_fault={"mode":"normal","delayMs":300,"jitterMs":0,"failRate":0.0,"status":500,"hangSeconds":300,"flapPeriodSeconds":10}
effective_virtual=false
```

| name | profile | VT | pool | scenario | rps | p50 ms | p95 ms | p99 ms | failed | dropped | maxVUs | app cpu peak | app mem |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E3-M-off-800 | M | false | 20(default) | 30-cpu-bound.js | 367.2 | 896.3 | 5279.7 | 6471.0 | 0% | 15701 | 2000 | 110.5% | 651.8MiB / 1GiB |
