// E9: 한계점 탐색 — 누적 threshold의 함정을 피하기 위해 "스텝별 시나리오"로 평가한다.
// (하나의 긴 ramp에서 threshold를 걸면 초반 정상 구간이 p99/에러율을 희석해 한계가 과대평가됨)
//
// STEPS개의 constant-arrival-rate 시나리오를 START_RPS→MAX_RPS로 순차 실행하고,
// threshold를 {scenario:stepNN}으로 스코프해 각 스텝을 독립 평가한다.
// abortOnFail이라 어떤 스텝이 깨지면 그 시점에 중단 → "마지막으로 통과한 스텝의 rps"가 한계값.
//   ENDPOINT=/api/io/sleep?ms=300 START_RPS=200 MAX_RPS=3000 STEPS=10 STEP_DUR=30s P99_MS=500 ERR_RATE=0.01
import http from 'k6/http';
import { BASE_URL, num, str } from './lib/config.js';

const ENDPOINT = str('ENDPOINT', '/api/io/sleep?ms=300');
const START = num('START_RPS', 200), MAX = num('MAX_RPS', 3000), STEPS = num('STEPS', 10);
const STEP_DUR_S = num('STEP_DUR_S', 30);
const P99 = num('P99_MS', 500), ERR = num('ERR_RATE', 0.01);
const MAX_VUS = num('MAX_VUS', 8000);

const scenarios = {};
const thresholds = {};
for (let i = 1; i <= STEPS; i++) {
  const name = `step${String(i).padStart(2, '0')}`;
  const rate = Math.round(START + ((MAX - START) * (i - 1)) / Math.max(1, STEPS - 1));
  scenarios[name] = {
    executor: 'constant-arrival-rate',
    rate, timeUnit: '1s', duration: `${STEP_DUR_S}s`,
    startTime: `${(i - 1) * STEP_DUR_S}s`,
    preAllocatedVUs: Math.min(MAX_VUS, Math.max(100, rate * 2)),   // rate×latency 여유 (측정 중 VU 신규 할당 최소화)
    maxVUs: MAX_VUS,
    tags: { step: name, rate: String(rate) },
  };
  // 스텝 스코프 threshold: 이 스텝의 샘플만으로 평가 → 깨지는 즉시 중단
  thresholds[`http_req_duration{scenario:${name}}`] = [{ threshold: `p(99)<${P99}`, abortOnFail: true, delayAbortEval: '15s' }];
  thresholds[`http_req_failed{scenario:${name}}`] = [{ threshold: `rate<${ERR}`, abortOnFail: true, delayAbortEval: '15s' }];
}

export const options = { scenarios, thresholds };

export default function () {
  http.get(`${BASE_URL}${ENDPOINT}`, { tags: { name: ENDPOINT.split('?')[0] } });
}
