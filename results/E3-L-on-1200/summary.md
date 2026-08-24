# E3-L-on-1200

```
name=E3-L-on-1200
date=2026-08-24T10:52:28+0900
git=37f56ff
profile=L cpus=2 mem=2g xmx=1g
VT=true
APP_JAVA_OPTS=-Xmx1g -XX:+UseG1GC
POOL_SIZE=20(default)
ISSUE_STRATEGY=default
# ISSUE_STRATEGY는 3주차 전략 구현 전까지 동작에 영향 없음 (property로만 소비)
extra_env=
scenario=load/30-cpu-bound.js
k6_extra=
MAX_RPS=1200
mock_fault={"mode":"normal","delayMs":300,"jitterMs":0,"failRate":0.0,"status":500,"hangSeconds":300,"flapPeriodSeconds":10}
effective_virtual=true
```

| name | profile | VT | pool | scenario | rps | p50 ms | p95 ms | p99 ms | failed | dropped | maxVUs | app cpu peak | app mem |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E3-L-on-1200 | L | true | 20(default) | 30-cpu-bound.js | 697.2 | 1.4 | 30.7 | 475.1 | 0% | 946 | 456 | 183.8% | 495.7MiB / 2GiB |
