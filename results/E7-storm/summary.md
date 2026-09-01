# E7-storm

```
name=E7-storm
date=2026-09-01T15:57:46+0900
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
| E7-storm | M | true | 20(default) | 55-idempotency-retry.js | 964.0 | 191.4 | 390.1 | 396.3 | 0% | 0 | 200 | 101.2% | 496.7MiB / 1GiB |
