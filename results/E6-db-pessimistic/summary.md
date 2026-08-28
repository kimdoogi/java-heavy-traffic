# E6-db-pessimistic

```
name=E6-db-pessimistic
date=2026-08-27T15:57:56+0900
git=4af7f05-dirty
profile=M cpus=1 mem=1g xmx=512m
VT=true
APP_JAVA_OPTS=-Xmx512m -XX:+UseG1GC
POOL_SIZE=50
ISSUE_STRATEGY=db-pessimistic
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
| E6-db-pessimistic | M | true | 50 | 50-flash-sale.js | 519.7 | 74.4 | 313.0 | 483.4 | 0% | 0 | 50 | 99.8% | 438.4MiB / 1GiB |
