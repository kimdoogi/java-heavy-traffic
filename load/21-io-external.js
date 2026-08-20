// E2/E8: 실제 다운스트림(mock-external) HTTP 대기. DELAY_MS 는 요청 파라미터로 전달(=mock 전역 설정보다 우선).
import http from 'k6/http';
import { BASE_URL, rampingRate, stepStages, num, str } from './lib/config.js';

const DELAY_MS = str('DELAY_MS', '');   // '' 이면 mock 전역 설정 사용 (E8 타임라인 실험)
const START = num('START_RPS', 100), MAX = num('MAX_RPS', 1500), STEP_DUR = __ENV.STEP_DUR || '30s';
export const options = {
  scenarios: {
    io_external: rampingRate({
      startRate: START,
      stages: stepStages(MAX, STEP_DUR),
      preAllocatedVUs: 300, maxVUs: num('MAX_VUS', 6000),
    }),
  },
  thresholds: { http_req_failed: ['rate<0.05'] },
};
export default function () {
  const q = DELAY_MS === '' ? '' : `?delayMs=${DELAY_MS}`;
  http.get(`${BASE_URL}/api/io/external${q}`, { tags: { name: '/api/io/external' } });
}
