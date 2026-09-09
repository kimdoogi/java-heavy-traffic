# E7-retry-on

```
name=E7-retry-on
date=2026-09-01T15:56:12+0900
git=7285cab-dirty
profile=M cpus=1 mem=1g xmx=512m
VT=true
APP_JAVA_OPTS=-Xmx512m -XX:+UseG1GC
POOL_SIZE=20(default)
ISSUE_STRATEGY=redis(default)
OPTIMISTIC_MAX_RETRIES=3(default)
extra_env=
scenario=load/55-idempotency-retry.js
k6_extra=
mock_fault={"mode":"normal","delayMs":300,"jitterMs":0,"failRate":0.0,"status":500,"hangSeconds":300,"flapPeriodSeconds":10}
effective_virtual=true
```

| name | profile | VT | pool | scenario | rps | p50 ms | p95 ms | p99 ms | failed | dropped | maxVUs | app cpu peak | app mem |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E7-retry-on | M | true | 20(default) | 55-idempotency-retry.js | 298.4 | 499.2 | 1851.1 | 2366.7 | 0% | 0 | 200 | 99.8% | 480.2MiB / 1GiB |
