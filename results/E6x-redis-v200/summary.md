# E6x-redis-v200

```
name=E6x-redis-v200
date=2026-08-27T17:20:26+0900
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
| E6x-redis-v200 | M | true | 80 | 50-flash-sale.js | 1059.9 | 108.7 | 505.6 | 682.6 | 0% | 0 | 200 | 101.2% | 485.7MiB / 1GiB |
