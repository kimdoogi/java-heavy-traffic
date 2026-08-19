# E1-pipeline-check

```
name=E1-pipeline-check
date=2026-08-19T22:14:16+0900
git=e49a151
profile=M cpus=1 mem=1g xmx=512m
VT=true
APP_JAVA_OPTS=-Xmx512m -XX:+UseG1GC
POOL_SIZE=20(default)
ISSUE_STRATEGY=default
extra_env=
scenario=load/10-baseline-ping.js
k6_extra=
MAX_RPS=2000
STEP_DUR=10s
```

| name | profile | VT | pool | scenario | rps | p50 ms | p95 ms | p99 ms | failed | dropped | maxVUs | app cpu peak | app mem |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E1-pipeline-check | M | true | 20(default) | 10-baseline-ping.js | 1219.9 | 0.4 | 1.1 | 3.6 | 0% | - | 100 | 40.8% | 396.6MiB / 1GiB |
