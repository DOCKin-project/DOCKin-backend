// 근로자 한 명의 아침 사이클을 한 반복(iteration)으로 밟는다 (measure/k6-load, E3).
//
//   로그인 → 내 근태 조회 → 출근 → 작업일지 목록 → 채팅방 목록 → 안전교육 목록
//
// 두 가지 부하 모양을 SCENARIO 로 고른다.
//   rate : constant-arrival-rate. 초당 RATE 명이 "아침 사이클"을 시작한다. 서버가 느려져도 도착률을
//          유지하므로(개방형) 무릎이 지연·에러로 드러난다. 가정 피크는 1,500명/1,800초 ≈ 0.83/s.
//   spike: ramping-arrival-rate. 1/s → SPIKE_RATE/s 를 10초 만에, SPIKE_HOLD 유지 뒤 복귀. 회복 시간을 본다.
//   vus  : ramping-vus. VU 한 명 = 앱을 켜 둔 근로자. 첫 반복에서만 로그인·출근하고 그 뒤로는
//          THINK 초 간격으로 목록을 다시 본다(폐쇄형, "동접"). TARGET_VUS 까지 램프.
//
// 계정은 k6u00001..k6u40000 (seed-users.sql). rate 는 반복 번호로, vus 는 VU 번호로 계정을 고른다.
// 한 단계 안에서 계정이 두 바퀴 돌면 출근이 409(AT001)가 되는데, 그 분기는 INSERT 가 아니라
// "이미 출근" 조회라 비용이 다르다. clockin_dup 로 따로 세고, 단계 사이에 reset-attendance.sql 을 돌린다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://127.0.0.1';
const USERS = Number(__ENV.USERS || 40000);
const SCENARIO = __ENV.SCENARIO || 'rate';
const RATE = Number(__ENV.RATE || 1);          // iter/s (rate)
const DURATION = __ENV.DURATION || '2m';
const TARGET_VUS = Number(__ENV.TARGET_VUS || 1000);
const RAMP = __ENV.RAMP || '1m';
const HOLD = __ENV.HOLD || '2m';
const THINK = Number(__ENV.THINK || 5);        // 초 (vus)
const OFFSET = Number(__ENV.OFFSET || 0);      // 계정 시작 오프셋 (단계마다 다른 구간을 쓰고 싶을 때)

const clockinDup = new Counter('clockin_dup');
const loginFail = new Counter('login_fail');
const journey = new Trend('journey_ms', true);
const NAMES = ['login', 'clockin', 'attendance_list', 'worklogs', 'chat_rooms', 'safety_courses'];

const scenarios = {
  rate: {
    executor: 'constant-arrival-rate',
    rate: RATE, timeUnit: '1s', duration: DURATION,
    // 사이클 하나가 ~0.3초면 필요한 VU ≈ RATE×0.3. 서버가 느려지면 그만큼 더 필요하다 — 부족하면
    // k6가 dropped_iterations 로 알려주고, 그건 "부하기가 못 따라간 것"이지 서버 무릎이 아니다.
    preAllocatedVUs: Math.max(20, Math.ceil(RATE * 2)), maxVUs: Number(__ENV.MAX_VUS || Math.max(200, RATE * 10)),
  },
  // 스파이크: 평시 1/s 에서 SPIKE_RATE 로 10초 만에 올라 SPIKE_HOLD 동안 버티고 다시 1/s. "07:00 정각에 문이 열리는" 모양.
  spike: {
    executor: 'ramping-arrival-rate',
    startRate: 1, timeUnit: '1s',
    stages: [{ duration: '20s', target: 1 }, { duration: '10s', target: Number(__ENV.SPIKE_RATE || 50) },
             { duration: __ENV.SPIKE_HOLD || '30s', target: Number(__ENV.SPIKE_RATE || 50) }, { duration: '10s', target: 1 }, { duration: '40s', target: 1 }],
    preAllocatedVUs: 100, maxVUs: Math.max(500, Number(__ENV.SPIKE_RATE || 50) * 10),
  },
  vus: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [{ duration: RAMP, target: TARGET_VUS }, { duration: HOLD, target: TARGET_VUS }, { duration: '20s', target: 0 }],
    gracefulRampDown: '10s',
  },
};
export const options = {
  scenarios: { [SCENARIO]: scenarios[SCENARIO] },
  // 판정 기준이 아니라 표시용이다. 무릎은 단계별 표를 보고 사람이 정한다.
  // 이름별 서브메트릭은 thresholds 에 적어야 요약에 나온다(k6 규칙) — 그래서 여섯 이름을 전부 건다.
  thresholds: Object.assign({ 'http_req_failed': ['rate<0.01'] },
    ...NAMES.flatMap((n) => [
      { [`http_req_duration{name:${n}}`]: ['p(95)<1000'] },
      { [`http_req_failed{name:${n}}`]: ['rate<0.01'] },
      { [`http_reqs{name:${n}}`]: ['count>=0'] },
    ])),
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: false,
};

function userId(n) { return 'k6u' + String(1 + ((OFFSET + n) % USERS)).padStart(5, '0'); }

function login(uid) {
  const r = http.post(`${BASE}/api/member/login`, JSON.stringify({ userId: uid, password: 'dockin1234' }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
  if (!check(r, { 'login 200': (x) => x.status === 200 })) { loginFail.add(1); return null; }
  return r.json('accessToken');
}
function auth(token, name) { return { headers: { Authorization: `Bearer ${token}` }, tags: { name } }; }

function clockin(token) {
  const r = http.post(`${BASE}/api/attendance/in`, JSON.stringify({ inLocation: '1도크 게이트' }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, tags: { name: 'clockin' } });
  if (r.status === 409) clockinDup.add(1);
  check(r, { 'clockin 200|409': (x) => x.status === 200 || x.status === 409 });
}
function browse(token) {
  const rs = http.batch([
    ['GET', `${BASE}/api/attendance`, null, auth(token, 'attendance_list')],
    ['GET', `${BASE}/api/work-logs?size=20`, null, auth(token, 'worklogs')],
    ['GET', `${BASE}/api/chat/rooms?size=20`, null, auth(token, 'chat_rooms')],
    ['GET', `${BASE}/api/safety/user/courses`, null, auth(token, 'safety_courses')],
  ]);
  rs.forEach((r, i) => check(r, { [`browse[${i}] 200`]: (x) => x.status === 200 }));
}

export default function () {
  if (SCENARIO === 'vus') {
    const vu = exec.vu.idInTest;
    if (!exec.vu.tags.token) {                   // 첫 반복: 로그인 + 출근
      const t0 = Date.now();
      const token = login(userId(vu));
      if (!token) { sleep(THINK); return; }
      exec.vu.tags.token = token;
      clockin(token);
      browse(token);
      journey.add(Date.now() - t0);
    } else {
      browse(exec.vu.tags.token);
    }
    sleep(THINK);
    return;
  }
  const t0 = Date.now();
  const token = login(userId(exec.scenario.iterationInTest));
  if (!token) return;
  clockin(token);
  browse(token);
  journey.add(Date.now() - t0);
}

// 요청 이름별 p50/p95/p99·건수·실패율만 남긴 요약. 원 JSON(--summary-export)은 1MB 가까이 돼 표로 못 본다.
export function handleSummary(data) {
  const m = data.metrics;
  const row = (k) => {
    const d = m[`http_req_duration{name:${k}}`], f = m[`http_req_failed{name:${k}}`], n = m[`http_reqs{name:${k}}`];
    if (!d) return null;
    return { name: k, n: n ? n.values.count : null, rps: n ? +n.values.rate.toFixed(1) : null,
      p50: +d.values.med.toFixed(1), p95: +d.values['p(95)'].toFixed(1), p99: +d.values['p(99)'].toFixed(1), max: +d.values.max.toFixed(0),
      fail: f ? +(f.values.rate * 100).toFixed(2) : null };
  };
  const out = {
    scenario: SCENARIO, rate: SCENARIO === 'spike' ? Number(__ENV.SPIKE_RATE || 50) : RATE, target_vus: TARGET_VUS, duration: DURATION,
    reqs: m.http_reqs.values.count, rps: +m.http_reqs.values.rate.toFixed(1),
    fail_pct: +(m.http_req_failed.values.rate * 100).toFixed(2),
    p95_all: +m.http_req_duration.values['p(95)'].toFixed(1),
    dropped: m.dropped_iterations ? m.dropped_iterations.values.count : 0,
    max_vus: m.vus_max ? m.vus_max.values.value : null,
    login_fail: m.login_fail ? m.login_fail.values.count : 0,
    clockin_dup: m.clockin_dup ? m.clockin_dup.values.count : 0,
    journey_p95: m.journey_ms ? +m.journey_ms.values['p(95)'].toFixed(0) : null,
    by_name: NAMES.map(row).filter(Boolean),
  };
  const file = __ENV.OUT || 'summary.json';
  return { [file]: JSON.stringify(out, null, 1), stdout: textSummary(out) };
}
function textSummary(o) {
  const l = [`\n== ${o.scenario} rate=${o.rate}/s vus=${o.target_vus} reqs=${o.reqs} rps=${o.rps} fail=${o.fail_pct}% p95=${o.p95_all}ms dropped=${o.dropped} maxVUs=${o.max_vus} login_fail=${o.login_fail} dup=${o.clockin_dup} journey_p95=${o.journey_p95}ms`];
  l.push('name             n      rps     p50     p95     p99     max   fail%');
  for (const r of o.by_name) l.push(`${r.name.padEnd(15)} ${String(r.n).padStart(6)} ${String(r.rps).padStart(7)} ${String(r.p50).padStart(7)} ${String(r.p95).padStart(7)} ${String(r.p99).padStart(7)} ${String(r.max).padStart(7)} ${String(r.fail).padStart(7)}`);
  return l.join('\n') + '\n';
}
