// E2: I/O bound (Thread.sleep) — 플랫폼 vs 버츄얼 쓰레드 핵심 비교.
// 플랫폼(200 threads) 이론 천장 = 200 / (SLEEP_MS/1000) rps. 예: 300ms → ~666 rps.
import http from 'k6/http';
import { BASE_URL, rampingRate, num } from './lib/config.js';

const SLEEP_MS = num('SLEEP_MS', 300);
const START = num('START_RPS', 100), MAX = num('MAX_RPS', 2000), STEP_DUR = __ENV.STEP_DUR || '30s';
export const options = {
  scenarios: {
    io_sleep: rampingRate({
      startRate: START,
      stages: [
        { target: MAX * 0.2, duration: STEP_DUR },
        { target: MAX * 0.4, duration: STEP_DUR },
        { target: MAX * 0.6, duration: STEP_DUR },
        { target: MAX * 0.8, duration: STEP_DUR },
        { target: MAX, duration: STEP_DUR },
        { target: MAX, duration: STEP_DUR },
      ],
      // 필요한 VU ≈ rate × latency. 2000rps × 0.3s = 600. 대기열이 생기면 더 필요하므로 여유.
      preAllocatedVUs: 300, maxVUs: num('MAX_VUS', 6000),
    }),
  },
  thresholds: { http_req_failed: ['rate<0.01'] },
};
export default function () {
  http.get(`${BASE_URL}/api/io/sleep?ms=${SLEEP_MS}`, { tags: { name: '/api/io/sleep' } });
}
