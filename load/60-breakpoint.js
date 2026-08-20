// E9: 한계점 탐색 — 스텝별 constant-arrival-rate 시나리오 + 시나리오 스코프 threshold.
// (하나의 긴 ramp에 누적 threshold를 걸면 초반 정상 구간이 희석시켜 과대평가되기 때문)
//
// 설계 주의점 (2차 리뷰 반영):
//  - VU는 시나리오 간 공유되지 않는다 → 사전할당은 rate×목표지연(P99_MS)으로 최소화, gracefulStop 3s로 스텝 겹침 차단
//  - delayAbortEval은 "테스트 시작" 기준이다 → 스텝 시작 오프셋 + 유예를 스텝별로 계산
//  - VU가 모자라 명목 rate를 못 넣은 스텝은 dropped_iterations threshold로 무효화 (한계 과대평가 방지)
//  - 한계를 찾으면 threshold abort(k6 exit 99)로 끝나는 것이 정상 — run-experiment.sh가 breakpoint에 한해 성공으로 처리
//
//   ENDPOINT='/api/io/sleep?ms=300' START_RPS=200 MAX_RPS=3000 STEPS=10 STEP_DUR_S=30 P99_MS=500 ERR_RATE=0.01
import http from 'k6/http';
import { BASE_URL, num, str } from './lib/config.js';

const ENDPOINT = str('ENDPOINT', '/api/io/sleep?ms=300');
const START = num('START_RPS', 200), MAX = num('MAX_RPS', 3000), STEPS = num('STEPS', 10);
// STEP_DUR_S(숫자 초) 우선. 다른 시나리오와 같은 표기인 STEP_DUR('30s' 또는 '30')도 허용한다.
const STEP_DUR_S = num('STEP_DUR_S', Number(str('STEP_DUR', '30').replace(/s$/, '')) || 30);
const P99 = num('P99_MS', 500), ERR = num('ERR_RATE', 0.01);
const MAX_VUS = num('MAX_VUS', 8000);

if (STEPS < 2) throw new Error(`STEPS는 2 이상 (현재 ${STEPS}) — 1개 스텝은 MAX_RPS를 시험하지 않는 무의미한 런이 된다`);
if (MAX <= START) throw new Error(`MAX_RPS(${MAX})는 START_RPS(${START})보다 커야 한다`);

const scenarios = {};
const thresholds = {};
for (let i = 1; i <= STEPS; i++) {
  const name = `step${String(i).padStart(2, '0')}`;
  const rate = Math.round(START + ((MAX - START) * (i - 1)) / (STEPS - 1));
  const startTime = (i - 1) * STEP_DUR_S;
  scenarios[name] = {
    executor: 'constant-arrival-rate',
    rate, timeUnit: '1s', duration: `${STEP_DUR_S}s`,
    startTime: `${startTime}s`,
    // k6는 모든 시나리오의 preAllocatedVUs를 시작 시 초기화한다 → 스텝 합계가 예산 (기본값에서 합 ≈2,000)
    preAllocatedVUs: Math.min(600, Math.max(50, Math.ceil((rate * P99) / 4000))),
    maxVUs: MAX_VUS,
    gracefulStop: '3s',
  };
  const grace = `${startTime + Math.min(15, Math.ceil(STEP_DUR_S / 2))}s`;
  thresholds[`http_req_duration{scenario:${name}}`] = [{ threshold: `p(99)<${P99}`, abortOnFail: true, delayAbortEval: grace }];
  thresholds[`http_req_failed{scenario:${name}}`] = [{ threshold: `rate<${ERR}`, abortOnFail: true, delayAbortEval: grace }];
  thresholds[`dropped_iterations{scenario:${name}}`] =
      [{ threshold: `count<${Math.max(1, Math.ceil(rate * STEP_DUR_S * 0.01))}`, abortOnFail: true, delayAbortEval: grace }];
}

export const options = { scenarios, thresholds };

export default function () {
  http.get(`${BASE_URL}${ENDPOINT}`, { tags: { name: ENDPOINT.split('?')[0] } });
}
