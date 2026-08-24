// E5: pinning 재현 — /pin/sync(synchronized+sleep → 캐리어 고정) vs /pin/lock(ReentrantLock → unmount).
// pinned 이론 천장 = 캐리어 수 / (PIN_MS/1000) rps. 예: 1cpu·50ms → 20 rps.
import http from 'k6/http';
import { BASE_URL, rampingRate, stepStages, num, str } from './lib/config.js';

const MODE = str('PIN_MODE', 'sync');   // sync | lock
const PIN_MS = num('PIN_MS', 50);
const START = num('START_RPS', 10), MAX = num('MAX_RPS', 200), STEP_DUR = __ENV.STEP_DUR || '30s';
export const options = {
  scenarios: {
    pin: rampingRate({
      startRate: START,
      stages: stepStages(MAX, STEP_DUR),
      preAllocatedVUs: 100, maxVUs: num('MAX_VUS', 2000),
    }),
  },
  thresholds: { http_req_failed: ['rate<0.01'] },
};
export default function () {
  http.get(`${BASE_URL}/api/pin/${MODE}?ms=${PIN_MS}`, { tags: { name: `/api/pin/${MODE}` } });
}
