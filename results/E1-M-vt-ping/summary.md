# E1-M-vt-ping

```
name=E1-M-vt-ping
date=2026-08-19T22:17:17+0900
git=e49a151
profile=M cpus=1 mem=1g xmx=512m
VT=true
APP_JAVA_OPTS=-Xmx512m -XX:+UseG1GC
POOL_SIZE=20(default)
ISSUE_STRATEGY=default
extra_env=
scenario=load/10-baseline-ping.js
k6_extra=
MAX_RPS=6000
START_RPS=500
STEP_DUR=20s
```

| name | profile | VT | pool | scenario | rps | p50 ms | p95 ms | p99 ms | failed | dropped | maxVUs | app cpu peak | app mem |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E1-M-vt-ping | M | true | 20(default) | 10-baseline-ping.js | 3611.3 | 0.3 | 22.9 | 87.5 | 0% | 3782 | 686 | 97.2% | 416.2MiB / 1GiB |
