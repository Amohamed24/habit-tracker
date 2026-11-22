// HF-25 (R1-17a): k6 load baseline for the toggle and list paths.
// Run it through ./perf/k6/run.sh, which pins the k6 image and records the environment and outbox lag.
//
// setup(): registers and logs in one user per VU through auth-service and creates one habit each.
// Load:    each VU toggles only its own habit (no two VUs race the same habit and day, so no
//          COMPLETION_CONFLICT), then lists its habits, then sleeps 1 s. The mix is 1 toggle + 1 list
//          per iteration. HF-27 reruns this file unchanged.
import http from 'k6/http';
import { check, fail, sleep } from 'k6';

const AUTH = __ENV.AUTH_URL || 'http://auth-service:8081';
const HABIT = __ENV.HABIT_URL || 'http://habit-service:8080';
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`; // unique emails per run (register rejects duplicates)
const VUS = parseInt(__ENV.LOAD_VUS || '50', 10); // the baseline is 50; overrides are for dry runs only
const DURATION = __ENV.LOAD_DURATION || '2m'; // the baseline is 2m; overrides are for dry runs only
const JSON_HDR = { 'Content-Type': 'application/json' };

export const options = {
  scenarios: {
    load: { executor: 'constant-vus', vus: VUS, duration: DURATION, gracefulStop: '10s' },
  },
  setupTimeout: '180s', // VUS × (register + login + create), with BCrypt on register and login
  thresholds: {
    'http_req_duration{endpoint:toggle}': ['p(95)<300'],
    'http_req_duration{endpoint:list}': ['p(95)<300'],
    // Load requests only: the setup calls (slow on purpose, BCrypt) don't count toward the error rate.
    'http_req_failed{phase:load}': ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const users = [];
  const t = { tags: { phase: 'setup' } };
  for (let i = 0; i < VUS; i++) {
    const body = JSON.stringify({ email: `k6-${RUN_ID}-${i}@perf.example`, password: 'k6-password' });
    const reg = http.post(`${AUTH}/api/auth/register`, body, { headers: JSON_HDR, ...t });
    if (reg.status !== 200) fail(`register ${i}: ${reg.status} ${reg.body}`);
    const login = http.post(`${AUTH}/api/auth/login`, body, { headers: JSON_HDR, ...t });
    if (login.status !== 200) fail(`login ${i}: ${login.status} ${login.body}`);
    const token = login.json('token');
    const habit = http.post(
      `${HABIT}/api/habits`,
      JSON.stringify({ name: `k6 habit ${i}`, description: 'HF-25 baseline', frequency: 'DAILY' }),
      { headers: { ...JSON_HDR, Authorization: `Bearer ${token}` }, ...t },
    );
    if (habit.status !== 200) fail(`create habit ${i}: ${habit.status} ${habit.body}`);
    users.push({ token, habitId: habit.json('id') });
  }
  return { users };
}

export default function (data) {
  const u = data.users[__VU - 1]; // __VU is 1..VUS: one user and one habit per VU
  const params = (endpoint, name) => ({
    headers: { Authorization: `Bearer ${u.token}` },
    tags: { phase: 'load', endpoint, name }, // name collapses /habits/{id}/toggle into one URL
  });

  const toggle = http.post(`${HABIT}/api/habits/${u.habitId}/toggle`, null,
    params('toggle', 'POST /api/habits/{id}/toggle'));
  check(toggle, { 'toggle 200': (r) => r.status === 200 });

  const list = http.get(`${HABIT}/api/habits`, params('list', 'GET /api/habits'));
  check(list, {
    'list 200': (r) => r.status === 200,
    'list has own habit': (r) => r.status === 200 && r.json().some((h) => h.id === u.habitId),
  });

  sleep(1);
}
