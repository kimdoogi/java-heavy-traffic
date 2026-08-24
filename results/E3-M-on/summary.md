# E3-M-on

```
name=E3-M-on
date=2026-08-24T10:35:01+0900
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
mock_fault={"mode":"normal","delayMs":300,"jitterMs":0,"failRate":0.0,"status":500,"hangSeconds":300,"flapPeriodSeconds":10}
effective_virtual=true
```

| name | profile | VT | pool | scenario | rps | p50 ms | p95 ms | p99 ms | failed | dropped | maxVUs | app cpu peak | app mem |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E3-M-on | M | true | 20(default) | 30-cpu-bound.js | 241.2 | 1.5 | 11.3 | 104.5 | 0% | 117 | 74 | 71.3% | 428.8MiB / 1GiB |
