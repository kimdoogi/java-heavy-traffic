// E6: 선착순 정합성 — N명이 쿠폰 M개를 동시 발급. 전략 4종(none/db-pessimistic/db-optimistic/redis) 비교.
// 정합성은 사후 count(coupon_issue) <= total 로 검증(scripts/verify-coupon.sh). 여기선 부하·응답분포·처리량 측정.
// sold_out(409)·retry_exhausted(503)은 정상 비즈니스 응답 → 기본 http_req_failed 대신 결과별 카운터로 집계하고,
// setResponseCallback으로 내장 실패 지표도 정직하게 만든다(안 그러면 4/5중 4가 sold_out이라 http_req_failed≈80%로 읽힘).
//
//   TOTAL=1000 VUS=1000 ITERS=5000 DURATION=3m \
//   scripts/run-experiment.sh -n E6-none --strategy none -s load/50-flash-sale.js
//   (COUPON_ID 지정 시 그 쿠폰에 발급, 미지정 시 setup에서 새로 생성)
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { BASE_URL, num, str } from './lib/config.js';

// 201 발급 / 409 품절·중복 / 503 재시도소진 은 모두 정상 응답 → 내장 실패 지표에서 제외.
http.setResponseCallback(http.expectedStatuses(201, 409, 503));

const COUPON_ID = num('COUPON_ID', 0);   // 0이면 setup에서 새로 생성
const TOTAL = num('TOTAL', 1000);        // COUPON_ID 미지정 시 생성할 쿠폰 수량
const VUS = num('VUS', 1000);
const ITERS = num('ITERS', 5000);
const DURATION = str('DURATION', '3m');  // db-pessimistic은 전 요청이 행 락에 직렬화 → 넉넉히

if (ITERS < TOTAL) {
  throw new Error(`ITERS(${ITERS})가 TOTAL(${TOTAL})보다 작으면 품절이 안 나 정합성 관찰이 무의미`);
}

const issued = new Counter('coupon_issued');
const soldOut = new Counter('coupon_sold_out');
const alreadyIssued = new Counter('coupon_already_issued');
const retryExhausted = new Counter('coupon_retry_exhausted');
const unexpected = new Counter('coupon_unexpected');

export const options = {
  scenarios: {
    // 선착순 몰림 재현: VU를 한 번에 투입해 ITERS건을 소화 (PLAN §4.2 "동시에 N명" = shared-iterations).
    flash_sale: { executor: 'shared-iterations', vus: VUS, iterations: ITERS, maxDuration: DURATION },
  },
  thresholds: {
    coupon_unexpected: ['count<1'],      // 예상 밖 응답(404/5xx) 1건이라도 나면 실패
    iterations: [`count>=${ITERS}`],     // maxDuration에 잘려 덜 돌면 실패 = 무효 런 조기 감지(count-verify 언더카운트 방지)
  },
};

export function setup() {
  if (COUPON_ID > 0) return { couponId: COUPON_ID };
  const res = http.post(`${BASE_URL}/api/coupons`,
      JSON.stringify({ name: 'flash-sale', totalQuantity: TOTAL }),
      { headers: { 'Content-Type': 'application/json' } });
  if (res.status !== 201) throw new Error(`쿠폰 생성 실패: ${res.status} ${res.body}`);
  const id = res.json('id');
  console.log(`flash-sale: created coupon id=${id} total=${TOTAL}`);
  return { couponId: id };
}

export default function (data) {
  // 전역 유니크 userId (1인 1매 — 중복 방지). iterationInTest는 0..ITERS-1로 유일.
  const userId = exec.scenario.iterationInTest + 1;
  const res = http.post(`${BASE_URL}/api/coupons/${data.couponId}/issue`,
      JSON.stringify({ userId }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: '/api/coupons/{id}/issue' } });

  // 카운터는 status 기준(견고). body 파싱 실패해도 분류가 깨지지 않게 try로 감싼다.
  switch (res.status) {
    case 201:
      issued.add(1);
      break;
    case 409: {
      let err = '';
      try { err = res.json('error'); } catch (_) { /* non-JSON 본문 방어 */ }
      (err === 'already_issued' ? alreadyIssued : soldOut).add(1);
      break;
    }
    case 503:
      retryExhausted.add(1);   // db-optimistic 재시도 소진
      break;
    default:
      unexpected.add(1);       // 404 / 5xx 등 예상 밖
      break;
  }
  check(res, { 'status is 201|409|503': (r) => [201, 409, 503].includes(r.status) });
}
