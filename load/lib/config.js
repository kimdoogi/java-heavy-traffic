// 공통 설정. 모든 시나리오가 import 한다.
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 기본 threshold: 실험별로 덮어쓴다. abortOnFail 은 breakpoint 류에서만 켠다.
export const DEFAULT_THRESHOLDS = {
  http_req_failed: ['rate<0.01'],
  http_req_duration: ['p(95)<300', 'p(99)<500'],
};

// open-model(도착률 고정) 시나리오 생성기. 서버가 느려져도 요청 투입량이 줄지 않아 한계 측정에 적합.
export function constantRate({ rate, duration = '2m', preAllocatedVUs = 200, maxVUs = 5000, exec }) {
  return {
    executor: 'constant-arrival-rate',
    rate, timeUnit: '1s', duration, preAllocatedVUs, maxVUs, ...(exec ? { exec } : {}),
  };
}

// 도착률을 단계적으로 올리는 시나리오 (breakpoint 탐색).
export function rampingRate({ startRate, stages, preAllocatedVUs = 200, maxVUs = 5000, exec }) {
  return {
    executor: 'ramping-arrival-rate',
    startRate, timeUnit: '1s', stages, preAllocatedVUs, maxVUs, ...(exec ? { exec } : {}),
  };
}

// 단계 램프 생성기: target은 반드시 정수여야 한다 (k6가 62.5 같은 값이면 파싱 단계에서 중단).
export function stepStages(max, stepDur, fractions = [0.25, 0.5, 0.75, 1, 1]) {
  return fractions.map((f) => ({ target: Math.round(max * f), duration: stepDur }));
}

export function num(name, def) {
  const v = __ENV[name];
  return v === undefined || v === '' ? def : Number(v);
}
export function str(name, def) {
  const v = __ENV[name];
  return v === undefined || v === '' ? def : v;
}
