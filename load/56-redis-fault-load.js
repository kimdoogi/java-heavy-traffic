// E8: Redis 장애 하 앱 거동 측정용 정상-부하 발생기 (open-model). 장애(docker pause / CPU throttle 등)는 외부에서
// 주입하고, 이 스크립트는 도착률 고정 constant-arrival-rate로 /issue를 쏴서 pileup·지연·503을 드러낸다.
// 도착률 고정이라 서버가 느려져도 투입량이 안 줄어 in-flight = rate×latency가 쌓임 → 한계·적체 관측에 적합.
//
//   COUPON_ID=1 RATE=400 DURATION=12s k6 run load/56-redis-fault-load.js
//   (COUPON_ID 미지정 시 setup에서 생성 — 단, 장애 주입 전에 미리 만들어 COUPON_ID로 넘겨야 pause 중 setup이 안 막힌다)
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { BASE_URL, num, str } from './lib/config.js';

http.setResponseCallback(http.expectedStatuses(201, 409, 503));

const COUPON_ID = num('COUPON_ID', 0);
const TOTAL = num('TOTAL', 500000);      // 재고 경쟁이 아니라 Redis 의존 경로 부하가 목적 → 크게
const RATE = num('RATE', 400);           // 초당 도착률 (open-model)
const DURATION = str('DURATION', '12s');
const MAXVUS = num('MAXVUS', 3000);      // 지연 시 in-flight 수용. 부족하면 dropped_iterations로 pileup이 드러난다

const ok = new Counter('fault_2xx');            // 201 발급/재생
const conflict = new Counter('fault_409');      // 409 (sold_out/already/in_progress)
const unavailable = new Counter('fault_503');   // 503 storage_unavailable (Redis 장애 fail-closed)
const other = new Counter('fault_other');       // 그 외(5xx 등)

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: Math.min(RATE, MAXVUS), maxVUs: MAXVUS,
    },
  },
  // threshold 없음 — 장애 주입 실험이라 503·dropped가 정상 관측 대상. 판단은 요약 수치로 한다.
};

export function setup() {
  if (COUPON_ID > 0) return { couponId: COUPON_ID };
  const res = http.post(`${BASE_URL}/api/coupons`, JSON.stringify({ name: 'redis-fault', totalQuantity: TOTAL }),
      { headers: { 'Content-Type': 'application/json' } });
  if (res.status !== 201) throw new Error(`쿠폰 생성 실패: ${res.status} ${res.body}`);
  return { couponId: res.json('id') };
}

export default function (data) {
  const n = exec.scenario.iterationInTest + 1;   // 전역 유니크 → 매 요청이 fresh claim+issue(Redis 3연산: SET NX·Lua·SET)
  const res = http.post(`${BASE_URL}/api/coupons/${data.couponId}/issue`,
      JSON.stringify({ userId: n }),
      { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `fault-${data.couponId}-${n}` },
        tags: { name: '/api/coupons/{id}/issue' } });
  if (res.status === 201) ok.add(1);
  else if (res.status === 409) conflict.add(1);
  else if (res.status === 503) unavailable.add(1);
  else other.add(1);
}
