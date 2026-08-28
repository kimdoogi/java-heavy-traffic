# E6x-redis-v50

```
name=E6x-redis-v50
date=2026-08-27T17:20:05+0900
git=89e8f7b
profile=M cpus=1 mem=1g xmx=512m
VT=true
APP_JAVA_OPTS=-Xmx512m -XX:+UseG1GC
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
| E6x-redis-v50 | M | true | 80 | 50-flash-sale.js | 626.6 | 70.0 | 295.3 | 479.7 | 0% | 0 | 50 | 102.6% | 466.6MiB / 1GiB |
