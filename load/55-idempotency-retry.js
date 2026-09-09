// E7: Idempotency-Key 재시도 폭주. 두 모드.
//  MODE=retry (기본, PLAN §4.3 스펙): per-vu-iterations — VU마다 고유 userId + 고유 키로 같은 /issue를 RETRIES회 순차 재시도.
//    VU 내 순차라 1회=실발급, 2회~=캐시 재생. "재생이 원본 201을 바이트 동일하게 돌려주는가(응답 일관성)"를 검증.
//  MODE=storm: shared-iterations — 다수 VU가 "하나의" 키+userId로 동시 폭격. 클레임 경합·409 request_in_progress·
//    동기 캐시저장(D5) 병목을 실측. 발급은 정확히 1건, 나머지는 201 재생 또는 409 in_progress, 5xx는 0이어야 한다.
//  USE_KEY=off: 대조군(멱등성 미사용) — retry에서 1×201 + N×409 already_issued로 "왜 응답이 불일치하는가"를 보여준다.
//
// 사후 정합성은 count(coupon_issue)로 본다(scripts/verify-coupon.sh). retry-on: count==VUS, storm-on: user 1의 발급==1.
//
//   VUS=200 RETRIES=10 scripts/run-experiment.sh -n E7-retry-on -s load/55-idempotency-retry.js
//   MODE=storm VUS=200 ITERS=4000 scripts/run-experiment.sh -n E7-storm -s load/55-idempotency-retry.js
//   USE_KEY=off VUS=200 RETRIES=10 scripts/run-experiment.sh -n E7-retry-off -s load/55-idempotency-retry.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';
import { BASE_URL, num, str } from './lib/config.js';

// 201 발급/재생, 409 in_progress·already_issued 는 모두 정상 응답 → 내장 실패 지표에서 제외.
http.setResponseCallback(http.expectedStatuses(201, 409, 503));

const MODE = str('MODE', 'retry');       // retry | storm
const USE_KEY = str('USE_KEY', 'on');    // on | off
const COUPON_ID = num('COUPON_ID', 0);   // 0이면 setup에서 새로 생성
const TOTAL = num('TOTAL', 200000);      // 품절이 멱등 측정을 오염시키지 않게 크게 (목표는 재고경쟁 아님)
const VUS = num('VUS', 200);
const RETRIES = num('RETRIES', 10);      // retry 모드: VU당 반복 (PLAN: per-vu 10회)
const ITERS = num('ITERS', 4000);        // storm 모드: 총 요청 수
const DURATION = str('DURATION', '2m');

const issued = new Counter('idem_issued');                 // 201 (실발급 또는 재생)
const alreadyIssued = new Counter('idem_already_issued');  // 409 already_issued
const inProgress = new Counter('idem_in_progress');        // 409 request_in_progress
const unexpected = new Counter('idem_unexpected');         // 그 외(5xx/404 등)
const inconsistent = new Counter('idem_inconsistent');     // retry: 같은 VU(같은 키) 내 응답 불일치
const firstLatency = new Trend('idem_first_latency', true);   // 첫 시도(실발급) 지연
const retryLatency = new Trend('idem_retry_latency', true);   // 재시도(재생) 지연

const scenario = MODE === 'storm'
  ? { retry_storm: { executor: 'shared-iterations', vus: VUS, iterations: ITERS, maxDuration: DURATION } }
  : { retry_seq: { executor: 'per-vu-iterations', vus: VUS, iterations: RETRIES, maxDuration: DURATION } };

const thresholds = { idem_unexpected: ['count<1'] };  // 예상 밖(5xx/404) 1건이라도 나면 실패
// 응답 일관성 threshold는 "retry + 멱등성 on"에서만 의미가 있다(off 대조군은 설계상 불일치, storm은 VU 교차 비교 불가).
if (MODE !== 'storm' && USE_KEY === 'on') {
  thresholds.idem_inconsistent = ['count<1'];
  thresholds.iterations = [`count>=${VUS * RETRIES}`];   // maxDuration에 잘려 덜 돌면 무효 런
}

export const options = { scenarios: scenario, thresholds };

export function setup() {
  if (COUPON_ID > 0) return { couponId: COUPON_ID };
  const res = http.post(`${BASE_URL}/api/coupons`,
      JSON.stringify({ name: 'idem-retry', totalQuantity: TOTAL }),
      { headers: { 'Content-Type': 'application/json' } });
  if (res.status !== 201) throw new Error(`쿠폰 생성 실패: ${res.status} ${res.body}`);
  const id = res.json('id');
  console.log(`idem: mode=${MODE} use_key=${USE_KEY} coupon=${id} vus=${VUS} retries=${RETRIES} iters=${ITERS}`);
  return { couponId: id };
}

// retry 모드: VU별 첫 응답을 기억해 재시도가 동일한지 비교한다.
// (k6 모듈 스코프는 VU별로 격리되고 그 VU의 반복 간에는 유지된다 — per-VU 상태의 표준 패턴.)
let firstBody = null;
let firstStatus = 0;

export default function (data) {
  // storm: 모두 같은 키+userId로 경합. retry: VU별 고유(1인 1매 재시도).
  // 키에 couponId를 넣어 런이 바뀌면 키도 바뀌게 한다 — 이전 런의 캐시(resultTtl 600s) 재생 오염 차단(advisor).
  const userId = MODE === 'storm' ? 1 : exec.vu.idInTest;
  const key = MODE === 'storm'
    ? `idem-${data.couponId}-storm`
    : `idem-${data.couponId}-${exec.vu.idInTest}`;
  const headers = { 'Content-Type': 'application/json' };
  if (USE_KEY === 'on') headers['Idempotency-Key'] = key;

  const res = http.post(`${BASE_URL}/api/coupons/${data.couponId}/issue`,
      JSON.stringify({ userId }), { headers, tags: { name: '/api/coupons/{id}/issue' } });

  const attempt = exec.vu.iterationInInstance;   // 0 = 첫 시도, 1.. = 재시도
  (attempt === 0 ? firstLatency : retryLatency).add(res.timings.duration);

  switch (res.status) {
    case 201:
      issued.add(1);
      break;
    case 409: {
      let err = '';
      try { err = res.json('error'); } catch (_) { /* non-JSON 본문 방어 */ }
      (err === 'request_in_progress' ? inProgress : alreadyIssued).add(1);
      break;
    }
    default:
      unexpected.add(1);
      break;
  }

  // 응답 일관성(retry 전용): 같은 VU(같은 키)의 첫 응답과 이후 응답이 상태·본문 모두 동일해야 한다.
  if (MODE !== 'storm') {
    if (attempt === 0) {
      firstStatus = res.status;
      firstBody = res.body;
    } else if (res.status !== firstStatus || res.body !== firstBody) {
      inconsistent.add(1);
    }
  }

  check(res, { 'status is 201|409': (r) => [201, 409].includes(r.status) });
}
