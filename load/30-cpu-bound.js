// E3: CPU bound. VT on/off 차이가 없어야 정상.
import http from 'k6/http';
import { BASE_URL, rampingRate, stepStages, num } from './lib/config.js';

const N = num('HASH_N', 20000);
const START = num('START_RPS', 20), MAX = num('MAX_RPS', 400), STEP_DUR = __ENV.STEP_DUR || '30s';
export const options = {
  scenarios: {
    cpu: rampingRate({
      startRate: START,
      stages: stepStages(MAX, STEP_DUR),
      preAllocatedVUs: 50, maxVUs: 2000,
    }),
  },
  thresholds: { http_req_failed: ['rate<0.01'] },
};
export default function () {
  http.get(`${BASE_URL}/api/cpu/hash?n=${N}`, { tags: { name: '/api/cpu/hash' } });
}
