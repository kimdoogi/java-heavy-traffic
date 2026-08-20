// E1: 프레임워크 자체 한계. /api/ping 에 도착률을 올려가며 투입.
import http from 'k6/http';
import { BASE_URL, rampingRate, stepStages, num } from './lib/config.js';

const START = num('START_RPS', 200), MAX = num('MAX_RPS', 4000), STEP_DUR = __ENV.STEP_DUR || '30s';
export const options = {
  scenarios: {
    ping: rampingRate({
      startRate: START,
      stages: stepStages(MAX, STEP_DUR),
      preAllocatedVUs: 100, maxVUs: 2000,
    }),
  },
  thresholds: { http_req_failed: ['rate<0.01'] },
};
export default function () {
  http.get(`${BASE_URL}/api/ping`, { tags: { name: '/api/ping' } });
}
