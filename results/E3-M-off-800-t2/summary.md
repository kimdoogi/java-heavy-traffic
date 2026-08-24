# E3-M-off-800-t2

```
name=E3-M-off-800-t2
date=2026-08-24T10:58:51+0900
git=37f56ff
profile=M cpus=1 mem=1g xmx=512m
VT=false
APP_JAVA_OPTS=-Xmx512m -XX:+UseG1GC
POOL_SIZE=20(default)
ISSUE_STRATEGY=default
# ISSUE_STRATEGY는 3주차 전략 구현 전까지 동작에 영향 없음 (property로만 소비)
extra_env=TOMCAT_MAX_THREADS=2
scenario=load/30-cpu-bound.js
k6_extra=
MAX_RPS=800
TOMCAT_MAX_THREADS=2
mock_fault={"mode":"normal","delayMs":300,"jitterMs":0,"failRate":0.0,"status":500,"hangSeconds":300,"flapPeriodSeconds":10}
effective_virtual=false
```

| name | profile | VT | pool | scenario | rps | p50 ms | p95 ms | p99 ms | failed | dropped | maxVUs | app cpu peak | app mem |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E3-M-off-800-t2 | M | false | 20(default) | 30-cpu-bound.js | 480.5 | 1.4 | 122.4 | 291.1 | 0% | 203 | 193 | 114.8% | 468MiB / 1GiB |
