# E6L-redis-v2000

```
name=E6L-redis-v2000
date=2026-08-27T17:50:44+0900
git=89e8f7b-dirty
profile=L cpus=2 mem=2g xmx=1g
VT=true
APP_JAVA_OPTS=-Xmx1g -XX:+UseG1GC
POOL_SIZE=80
ISSUE_STRATEGY=redis
OPTIMISTIC_MAX_RETRIES=3(default)
extra_env=
scenario=load/50-flash-sale.js
k6_extra=
DURATION=5m
mock_fault={"mode":"normal","delayMs":300,"jitterMs":0,"failRate":0.0,"status":500,"hangSeconds":300,"flapPeriodSeconds":10}
effective_virtual=true
```

| name | profile | VT | pool | scenario | rps | p50 ms | p95 ms | p99 ms | failed | dropped | maxVUs | app cpu peak | app mem |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E6L-redis-v2000 | L | true | 80 | 50-flash-sale.js | 3006.7 | 372.7 | 1966.1 | 2057.3 | 0% | 0 | 2000 | 204.1% | 955MiB / 2GiB |
