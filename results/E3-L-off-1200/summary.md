# E3-L-off-1200

```
name=E3-L-off-1200
date=2026-08-24T10:55:09+0900
git=37f56ff
profile=L cpus=2 mem=2g xmx=1g
VT=false
APP_JAVA_OPTS=-Xmx1g -XX:+UseG1GC
POOL_SIZE=20(default)
ISSUE_STRATEGY=default
# ISSUE_STRATEGY는 3주차 전략 구현 전까지 동작에 영향 없음 (property로만 소비)
extra_env=
scenario=load/30-cpu-bound.js
k6_extra=
MAX_RPS=1200
mock_fault={"mode":"normal","delayMs":300,"jitterMs":0,"failRate":0.0,"status":500,"hangSeconds":300,"flapPeriodSeconds":10}
effective_virtual=false
```

| name | profile | VT | pool | scenario | rps | p50 ms | p95 ms | p99 ms | failed | dropped | maxVUs | app cpu peak | app mem |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E3-L-off-1200 | L | false | 20(default) | 30-cpu-bound.js | 685.6 | 2.4 | 2043.0 | 2676.0 | 0% | 4414 | 2000 | 230.3% | 725.7MiB / 2GiB |
