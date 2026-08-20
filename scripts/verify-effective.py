#!/usr/bin/env python3
"""effective-env.json(/actuator/env, show-values=always)의 systemEnvironment와 요청 KEY=VAL 대조.
사용: verify-effective.py <effective-env.json> KEY=VAL [KEY=VAL ...]  — 불일치 시 exit 1"""
import json, sys

doc = json.load(open(sys.argv[1]))
env = {}
for ps in doc.get("propertySources", []):
    if ps.get("name") == "systemEnvironment":
        for k, v in ps.get("properties", {}).items():
            env[k] = str(v.get("value"))
if not env:
    sys.exit("verify-effective: systemEnvironment 소스가 비어 있음 — show-values 설정/스냅샷 확인")

mismatch = []
for arg in sys.argv[2:]:
    k, want = arg.split("=", 1)
    got = env.get(k)
    if got is None:
        mismatch.append(f"{k}: 컨테이너 env에 없음 (요청 {want})")
    elif got != want:
        mismatch.append(f"{k}: 요청 {want} vs 실효 {got}")
if mismatch:
    sys.exit("실효 설정 불일치 (--skip-up으로 재적용이 생략됐거나 컨테이너 미갱신):\n  " + "\n  ".join(mismatch))
print(f"   effective-env 검증 OK ({len(sys.argv) - 2}개 키)")
