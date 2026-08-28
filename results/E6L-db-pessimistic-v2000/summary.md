# E6L-db-pessimistic-v2000

```
name=E6L-db-pessimistic-v2000
date=2026-08-27T17:50:25+0900
git=89e8f7b-dirty
profile=L cpus=2 mem=2g xmx=1g
VT=true
APP_JAVA_OPTS=-Xmx1g -XX:+UseG1GC
POOL_SIZE=80
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
| E6L-db-pessimistic-v2000 | L | true | 80 | 50-flash-sale.js | 1327.4 | 1309.3 | 3048.9 | 3331.1 | 7.67% | 0 | 2000 | 202.5% | 979.5MiB / 2GiB |
