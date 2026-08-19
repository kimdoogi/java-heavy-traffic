// E1: 툴체인/엔드포인트 동작 확인. 1 VU, 30s, 모든 실험 엔드포인트를 한 번씩.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL } from './lib/config.js';

export const options = {
  vus: 1,
  duration: __ENV.DURATION || '30s',
  thresholds: { http_req_failed: ['rate<0.01'], checks: ['rate>0.99'] },
};

export default function () {
  const paths = [
    '/api/ping',
    '/api/io/sleep?ms=50',
    '/api/io/external?delayMs=50',
    '/api/cpu/hash?n=2000',
    '/api/pin/sync?ms=10',
    '/api/pin/lock?ms=10',
    '/api/db/ping',
  ];
  for (const p of paths) {
    const res = http.get(`${BASE_URL}${p}`, { tags: { name: p.split('?')[0] } });
    check(res, { [`${p} 200`]: (r) => r.status === 200 });
  }
  sleep(0.5);
}
