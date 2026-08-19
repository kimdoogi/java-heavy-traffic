// E9: 한계점 탐색. ENDPOINT 를 지정해 도착률을 선형 증가시키고, threshold 초과 시 자동 중단(abortOnFail).
//   ENDPOINT=/api/io/sleep?ms=300 MAX_RPS=3000 RAMP=5m P99_MS=500 ERR_RATE=0.01
import http from 'k6/http';
import { BASE_URL, num, str } from './lib/config.js';

const ENDPOINT = str('ENDPOINT', '/api/io/sleep?ms=300');
const START = num('START_RPS', 50), MAX = num('MAX_RPS', 3000), RAMP = str('RAMP', '5m');
const P99 = num('P99_MS', 500), ERR = num('ERR_RATE', 0.01);
export const options = {
  scenarios: {
    breakpoint: {
      executor: 'ramping-arrival-rate',
      startRate: START, timeUnit: '1s',
      stages: [{ target: MAX, duration: RAMP }],
      preAllocatedVUs: 300, maxVUs: num('MAX_VUS', 8000),
    },
  },
  thresholds: {
    http_req_failed: [{ threshold: `rate<${ERR}`, abortOnFail: true, delayAbortEval: '20s' }],
    http_req_duration: [{ threshold: `p(99)<${P99}`, abortOnFail: true, delayAbortEval: '20s' }],
  },
};
export default function () {
  http.get(`${BASE_URL}${ENDPOINT}`, { tags: { name: ENDPOINT.split('?')[0] } });
}
