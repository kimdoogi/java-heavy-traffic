#!/usr/bin/env python3
"""results/<name>/summary.json + docker-stats.csv + meta.env → summary.md (표 한 줄) & stdout"""
import json, sys, os, re

out = sys.argv[1]
meta = dict(l.split("=", 1) for l in open(f"{out}/meta.env", encoding="utf-8").read().splitlines() if "=" in l)
try:
    s = json.load(open(f"{out}/summary.json"))
except FileNotFoundError:
    sys.exit(f"summarize: {out}/summary.json 없음 — k6가 summary를 만들지 못함 (k6.log 확인)")
m = s.get("metrics", {})

def g(name, key, default=float("nan")):
    return m.get(name, {}).get(key, default)

# k6 v1+ summary.json: {"metrics": {"http_reqs": {"count":..,"rate":..}, "http_req_duration": {"p(95)":..}}}
def trend(name, key):
    v = m.get(name, {})
    if key in v: return v[key]
    return v.get("values", {}).get(key, float("nan"))

rps = trend("http_reqs", "rate")
count = trend("http_reqs", "count")
p50 = trend("http_req_duration", "med")
p95 = trend("http_req_duration", "p(95)")
p99 = trend("http_req_duration", "p(99)")
failed = trend("http_req_failed", "value")
if failed != failed:  # NaN → try rate
    failed = trend("http_req_failed", "rate")
dropped = trend("dropped_iterations", "count")
if dropped != dropped: dropped = 0  # 메트릭 부재 = 드롭 0
vus_max = trend("vus_max", "max")
if vus_max != vus_max: vus_max = trend("vus_max", "value")

# docker stats peak for coupon-api
peak_cpu, peak_mem = float("nan"), ""
try:
    cpu_max, mem_at_max = -1.0, ""
    for line in open(f"{out}/docker-stats.csv", encoding="utf-8"):
        parts = line.strip().split(",")
        if len(parts) < 4 or "coupon-api" not in parts[1]: continue
        try:
            cpu = float(parts[2].rstrip("%"))
        except ValueError:      # 컨테이너 재시작/OOM 중이면 docker stats가 '--' 를 찍는다
            continue
        if cpu > cpu_max: cpu_max, mem_at_max = cpu, parts[3]
    if cpu_max >= 0: peak_cpu, peak_mem = cpu_max, mem_at_max
except FileNotFoundError:
    pass

def fmt(x, d=1):
    return "-" if x != x else (f"{x:.{d}f}" if isinstance(x, float) else str(x))

row = (f"| {meta.get('name')} | {meta.get('profile','').split()[0]} | {meta.get('VT')} | {meta.get('POOL_SIZE')} | "
       f"{os.path.basename(meta.get('scenario',''))} | {fmt(rps)} | {fmt(p50)} | {fmt(p95)} | {fmt(p99)} | "
       f"{fmt(failed*100 if failed==failed else failed,2)}% | {fmt(dropped,0)} | {fmt(vus_max,0)} | {fmt(peak_cpu)}% | {peak_mem} |")
header = ("| name | profile | VT | pool | scenario | rps | p50 ms | p95 ms | p99 ms | failed | dropped | maxVUs | app cpu peak | app mem |\n"
          "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
md = f"# {meta.get('name')}\n\n```\n" + open(f"{out}/meta.env", encoding="utf-8").read() + "```\n\n" + header + "\n" + row + "\n"
open(f"{out}/summary.md", "w", encoding="utf-8").write(md)
print(header); print(row)
