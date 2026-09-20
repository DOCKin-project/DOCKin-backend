// 무작위 트래픽 생성기 (measure/k6-load). "트래픽이 없으면 만들어라" — 대시보드·슬로우 쿼리 보고·경보가
// 실제 요청으로 움직이는지 보려고 초당 RATE 건(기본 1,000)을 DURATION(기본 10m) 동안 흩뿌린다.
//
//   k6 run measure/k6-load/traffic.js                                  # 127.0.0.1:80(nginx) 에 1,000/s × 10분
//   RATE=100 DURATION=2m k6 run measure/k6-load/traffic.js             # 로컬 compose 는 이 정도부터
//   BASE_URL=http://10.0.1.23 USERS=40000 k6 run measure/k6-load/traffic.js
//   USER_IDS=worker01,worker02,worker03 RATE=50 k6 run measure/k6-load/traffic.js   # k6u 계정 풀 없이(시드 계정만)
//
// journey.js 가 "근로자 한 명의 아침"을 순서대로 밟는 것과 달리, 여기서는 반복 하나 = 요청 하나다. 그래서
// RATE 가 곧 req/s 다. 무작위는 네 군데에 들어간다.
//   도착률   PATTERN=random(기본): STEP(15s)마다 목표를 RATE×[1-JITTER, 1+JITTER](기본 ±50%) 에서 뽑아
//            ramping-arrival-rate 로 잇는다 — 평균은 RATE, 모양은 매번 다르다(SEED 로 고정 가능). flat 이면 일정.
//   엔드포인트 MIX 표의 가중치로 뽑는다. 근로자 앱이 실제로 치는 비율을 흉내 냈다 — 목록 조회가 대부분, 쓰기는
//            출퇴근뿐, 로그인은 2%(bcrypt 가 건당 수십 ms 라 1,000/s 를 전부 로그인으로 채우면 서버 CPU 가
//            로그인만 하다 끝난다). AI 엔드포인트(/api/ai/**)는 외부 모델을 부르고 한도(#43~#57)가 있어 뺐다.
//   계정     VU 마다 풀(k6u00001..USERS 또는 USER_IDS)에서 무작위로 골라 로그인한 세션을 들고 있다가, 'login'
//            이 뽑히면 다른 계정으로 갈아탄다. 401 이면 그 자리에서 다시 로그인한다.
//   파라미터 검색어·기간·페이지 크기도 목록에서 뽑는다. 검색어는 시드에 *있는* 낱말만 쓴다 — 구역에 없는 키워드는
//            정렬 인덱스를 끝까지 걷는 경로라(#154) 1,000/s 에 섞으면 그것만 재게 된다.
//
// 쓰기의 뒷정리: 출근·퇴근은 하루 한 번이라 두 번째부터는 409(AT001/AT003) 다. 그건 실패가 아니라 그 분기의
// 정상 응답이므로 k6 실패에서 빼고(expectedStatuses) dup 로 따로 센다. 다음 실행 전에 비우려면 reset-attendance.sql.
// 채팅 메시지 조회는 방이 있어야 하는데 풀 계정에는 방이 없다. 그래서 VU 마다 채팅용 세션을 하나 따로 두고, 그
// 계정에 방이 없으면 하나 만든다(CHAT_CREATE_ROOMS=0 이면 안 만들고 목록 조회로 대신한다). 방 수 = VU 수다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://127.0.0.1';
const RATE = Number(__ENV.RATE || 1000);            // req/s 평균
const DURATION_S = toSeconds(__ENV.DURATION || '10m');
const PATTERN = __ENV.PATTERN || 'random';           // random | flat
const JITTER = Number(__ENV.JITTER || 0.5);          // random 일 때 ±비율
const STEP_S = Number(__ENV.STEP || 15);             // random 일 때 목표를 바꾸는 간격(초)
const SEED = Number(__ENV.SEED || Date.now());
const USERS = Number(__ENV.USERS || 40000);          // k6u00001..k6uUSERS (seed-users.sql)
const USER_IDS = (__ENV.USER_IDS || '').split(',').map((s) => s.trim()).filter(Boolean);
const PASSWORD = __ENV.PASSWORD || 'dockin1234';
const LOGIN_PCT = Number(__ENV.LOGIN_PCT || 2);      // 요청 중 로그인 비율(%)
const CHAT_CREATE_ROOMS = __ENV.CHAT_CREATE_ROOMS !== '0';
const MAX_VUS = Number(__ENV.MAX_VUS || Math.max(200, RATE * 2));

// 엔드포인트 가중치. 합이 100 일 필요는 없다. 이름은 요약 표의 행이 된다.
const MIX = [
  ['attendance_list', 14], ['worklogs', 14], ['worklogs_page2', 4], ['worklog_search', 8], ['worklog_comments', 5],
  ['worklogs_others', 4], ['chat_rooms', 12], ['chat_messages', 10], ['safety_courses', 6], ['safety_search', 3],
  ['safety_uncompleted', 3], ['calendar', 4], ['absence_list', 4], ['health', 3],
  ['clockin', 2], ['clockout', 1], ['refresh', 1], ['login', LOGIN_PCT],
];
const NAMES = MIX.map(([n]) => n).concat(['chat_room_create']);
const WORKLOG_KEYWORDS = ['용접', '와이어', '롤러', '압력', '송급', '확인', '작업일지', '부하'];
const SAFETY_KEYWORDS = ['안전', '밀폐', '용접', '추락', '크레인', '도장', '화기', '중량물'];
const LOCATIONS = ['1도크 게이트', '2도크 게이트', '3도크 게이트', '본관 정문'];
const PAGE_SIZES = [10, 20, 20, 20, 50];

// 시드 있는 난수(mulberry32) — 도착률 모양을 SEED 로 재현하려고. 요청 안의 무작위는 Math.random 이면 된다.
function rng(seed) { let a = seed >>> 0; return () => { a = (a + 0x6D2B79F5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
function toSeconds(s) { const m = /^(\d+)(ms|s|m|h)?$/.exec(String(s).trim()); if (!m) throw new Error(`DURATION 형식: ${s}`); return Number(m[1]) * ({ ms: 0.001, s: 1, m: 60, h: 3600 }[m[2] || 's']); }
function pick(arr) { return arr[Math.floor(Math.random() * arr.length)]; }
function weighted(mix) {
  const total = mix.reduce((s, [, w]) => s + w, 0);
  let r = Math.random() * total;
  for (const [name, w] of mix) { r -= w; if (r < 0) return name; }
  return mix[mix.length - 1][0];
}

function stages() {
  if (PATTERN === 'flat') return [{ duration: `${DURATION_S}s`, target: RATE }];
  const r = rng(SEED); const out = [];
  for (let t = 0; t < DURATION_S; t += STEP_S) {
    const d = Math.min(STEP_S, DURATION_S - t);
    out.push({ duration: `${d}s`, target: Math.max(1, Math.round(RATE * (1 + JITTER * (2 * r() - 1)))) });
  }
  return out;
}

export const options = {
  scenarios: {
    traffic: {
      executor: 'ramping-arrival-rate',
      startRate: RATE, timeUnit: '1s',
      stages: stages(),
      // 요청 하나가 ~50ms 면 1,000/s 에 VU 50 이면 되지만 서버가 느려지면 그만큼 더 든다. 모자라면 k6 가
      // dropped_iterations 로 알려준다 — 그건 부하기가 못 따라간 것이지 서버가 아니다.
      preAllocatedVUs: Math.max(50, Math.ceil(RATE / 4)), maxVUs: MAX_VUS,
      gracefulStop: '10s',
    },
  },
  thresholds: Object.assign({ 'http_req_failed': ['rate<0.05'] },
    ...NAMES.flatMap((n) => [
      { [`http_req_duration{name:${n}}`]: ['p(95)<2000'] },
      { [`http_req_failed{name:${n}}`]: ['rate<0.05'] },
      { [`http_reqs{name:${n}}`]: ['count>=0'] },
    ])),
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: false,
};

const loginFail = new Counter('login_fail');
const refreshFail = new Counter('refresh_fail');
const attendanceDup = new Counter('attendance_dup');
const relogin401 = new Counter('relogin_401');
const status4xx = new Counter('status_4xx');
const status5xx = new Counter('status_5xx');

// VU 하나가 들고 있는 세션. k6 는 VU 마다 JS 런타임이 따로라 모듈 변수가 곧 VU 상태다.
// session 은 'login' 이 뽑힐 때마다 다른 계정으로 갈아타고, chatSession 은 방을 가진 계정으로 고정이다.
let session = null;
let chatSession = null;
const ATTENDANCE_OK = http.expectedStatuses(200, 404, 409);  // AT001·AT003(이미 처리)·AT002(출근 전 퇴근)

function randomUserId() {
  if (USER_IDS.length) return pick(USER_IDS);
  return 'k6u' + String(1 + Math.floor(Math.random() * USERS)).padStart(5, '0');
}
function jsonHeaders(token) {
  const h = { 'Content-Type': 'application/json' };
  if (token) h.Authorization = `Bearer ${token}`;
  return h;
}
function tag(name, token) { return { headers: jsonHeaders(token), tags: { name } }; }
function classify(r) {
  if (r.status >= 500) status5xx.add(1); else if (r.status >= 400) status4xx.add(1);
  return r;
}

function login(name, uid) {
  uid = uid || randomUserId();
  const r = classify(http.post(`${BASE}/api/member/login`, JSON.stringify({ userId: uid, password: PASSWORD }), tag(name || 'login')));
  if (!check(r, { 'login 200': (x) => x.status === 200 })) { loginFail.add(1); return null; }
  return { userId: uid, access: r.json('accessToken'), refresh: r.json('refreshToken'), roomId: null, logIds: [], cursor: null, triedRoom: false };
}
function ensureSession() {
  if (!session) session = login('login');
  return session;
}

// 401 이면 세션이 죽은 것(만료·다른 VU 가 같은 계정으로 로그인해 refresh 를 덮음 등) — 다시 로그인하고 한 번 더.
function withAuth(name, fn) {
  const s = ensureSession(); if (!s) return null;
  let r = classify(fn(s));
  if (r.status === 401) {
    relogin401.add(1); session = login('login');
    if (session) r = classify(fn(session));
  }
  return r;
}
function ok200(name, r) { if (r) check(r, { [`${name} 200`]: (x) => x.status === 200 }); }

// 첫 페이지에서 댓글 조회에 쓸 logId 들과 다음 페이지 커서(마지막 항목의 createdAt+logId — 둘 다 있어야 한다,
// 하나만 주면 WorkLogCursor 가 400)를 기억한다.
function rememberLogIds(r) {
  if (!r || r.status !== 200 || !session) return;
  try {
    const c = r.json('content');
    if (Array.isArray(c) && c.length) {
      session.logIds = c.slice(0, 20).map((w) => w.logId).filter(Boolean);
      const last = c[c.length - 1];
      session.cursor = last.createdAt && last.logId ? { createdAt: last.createdAt, logId: last.logId } : null;
    }
  } catch (e) { /* 본문이 목록이 아니면 무시 */ }
}
function ensureRoom() {
  const s = chatSession; if (s.roomId || s.triedRoom) return s.roomId;
  s.triedRoom = true;
  const r = classify(http.get(`${BASE}/api/chat/rooms?size=5`, tag('chat_rooms', s.access)));
  if (r.status === 200) {
    const c = r.json('content'); if (Array.isArray(c) && c.length) { s.roomId = c[0].roomId; return s.roomId; }
  }
  if (!CHAT_CREATE_ROOMS) return null;
  const mate = randomUserId();
  const cr = classify(http.post(`${BASE}/api/chat/room`, JSON.stringify({ room_name: `부하 ${s.userId}`, participantIds: [s.userId, mate] }), tag('chat_room_create', s.access)));
  if (cr.status === 200 || cr.status === 201) s.roomId = cr.json('roomId');
  return s.roomId;
}
function ensureChat() {
  if (!chatSession) chatSession = login('login');
  if (!chatSession) return null;
  return ensureRoom() ? chatSession : null;
}
function recentRange() {
  const days = 1 + Math.floor(Math.random() * 30); const to = new Date(); const from = new Date(to.getTime() - days * 86400000);
  const d = (x) => x.toISOString().slice(0, 10);
  return `from=${d(from)}&to=${d(to)}`;
}

const ACTIONS = {
  login() { const s = login('login'); if (s) session = s; },
  refresh() {
    const s = ensureSession(); if (!s) return;
    const r = classify(http.post(`${BASE}/api/member/refresh`, JSON.stringify({ refreshToken: s.refresh }), tag('refresh')));
    if (r.status === 200) { s.access = r.json('accessToken'); s.refresh = r.json('refreshToken') || s.refresh; }
    else { refreshFail.add(1); session = login('login'); }
  },
  health() { ok200('health', classify(http.get(`${BASE}/actuator/health`, { tags: { name: 'health' } }))); },
  attendance_list() { ok200('attendance_list', withAuth('attendance_list', (s) => http.get(`${BASE}/api/attendance?${recentRange()}`, tag('attendance_list', s.access)))); },
  calendar() { ok200('calendar', withAuth('calendar', (s) => http.get(`${BASE}/api/attendance/calendar?year=${pick([2026, 2026, 2026, 2025])}`, tag('calendar', s.access)))); },
  clockin() {
    const r = withAuth('clockin', (s) => http.post(`${BASE}/api/attendance/in`, JSON.stringify({ inLocation: pick(LOCATIONS) }), Object.assign(tag('clockin', s.access), { responseCallback: ATTENDANCE_OK })));
    if (!r) return; if (r.status === 409) attendanceDup.add(1);
    check(r, { 'clockin 200|409': (x) => x.status === 200 || x.status === 409 });
  },
  clockout() {
    const r = withAuth('clockout', (s) => http.post(`${BASE}/api/attendance/out`, JSON.stringify({ outLocation: pick(LOCATIONS) }), Object.assign(tag('clockout', s.access), { responseCallback: ATTENDANCE_OK })));
    if (!r) return; if (r.status === 409) attendanceDup.add(1);
    check(r, { 'clockout 200|404|409': (x) => x.status === 200 || x.status === 404 || x.status === 409 }); // 404 AT002: 출근 전 퇴근
  },
  worklogs() { const r = withAuth('worklogs', (s) => http.get(`${BASE}/api/work-logs?size=${pick(PAGE_SIZES)}`, tag('worklogs', s.access))); ok200('worklogs', r); rememberLogIds(r); },
  worklogs_page2() {                                 // 커서 페이징: 첫 페이지의 마지막 항목 뒤부터
    const s = ensureSession(); if (!s) return;
    if (!s.cursor) return ACTIONS.worklogs();        // 아직 첫 페이지를 안 봤으면 그것부터
    const r = classify(http.get(`${BASE}/api/work-logs?size=20&beforeCreatedAt=${encodeURIComponent(s.cursor.createdAt)}&beforeLogId=${s.cursor.logId}`, tag('worklogs_page2', s.access)));
    if (r.status === 401) { relogin401.add(1); session = login('login'); return ACTIONS.worklogs(); }
    ok200('worklogs_page2', r);
  },
  worklog_search() { ok200('worklog_search', withAuth('worklog_search', (s) => http.get(`${BASE}/api/work-logs/search?keyword=${encodeURIComponent(pick(WORKLOG_KEYWORDS))}&size=20`, tag('worklog_search', s.access)))); },
  worklogs_others() { ok200('worklogs_others', withAuth('worklogs_others', (s) => http.get(`${BASE}/api/work-logs/others/${randomUserId()}?size=20`, tag('worklogs_others', s.access)))); },
  worklog_comments() {
    const s = ensureSession(); if (!s) return;
    if (!s.logIds.length) return ACTIONS.worklogs();  // 아직 목록을 안 봤으면 목록부터
    const r = classify(http.get(`${BASE}/api/work-logs/${pick(s.logIds)}/comments`, tag('worklog_comments', s.access)));
    if (r.status === 401) { relogin401.add(1); session = login('login'); return ACTIONS.worklogs(); }
    ok200('worklog_comments', r);
  },
  chat_rooms() { ok200('chat_rooms', withAuth('chat_rooms', (s) => http.get(`${BASE}/api/chat/rooms?size=${pick(PAGE_SIZES)}`, tag('chat_rooms', s.access)))); },
  chat_messages() {
    const c = ensureChat(); if (!c) return ACTIONS.chat_rooms();
    let r = classify(http.get(`${BASE}/api/chat/room/${c.roomId}/messages?size=20`, tag('chat_messages', c.access)));
    if (r.status === 401) {                          // 채팅 세션은 같은 계정으로 다시 로그인한다(방을 지키려고)
      relogin401.add(1); const again = login('login', c.userId);
      if (again) { chatSession = Object.assign(again, { roomId: c.roomId, triedRoom: true }); r = classify(http.get(`${BASE}/api/chat/room/${c.roomId}/messages?size=20`, tag('chat_messages', chatSession.access))); }
    }
    ok200('chat_messages', r);
  },
  safety_courses() { ok200('safety_courses', withAuth('safety_courses', (s) => http.get(`${BASE}/api/safety/user/courses?size=${pick(PAGE_SIZES)}`, tag('safety_courses', s.access)))); },
  safety_search() { ok200('safety_search', withAuth('safety_search', (s) => http.get(`${BASE}/api/safety/user/courses/search?keyword=${encodeURIComponent(pick(SAFETY_KEYWORDS))}`, tag('safety_search', s.access)))); },
  safety_uncompleted() { ok200('safety_uncompleted', withAuth('safety_uncompleted', (s) => http.get(`${BASE}/api/safety/user/training/uncompleted`, tag('safety_uncompleted', s.access)))); },
  absence_list() { ok200('absence_list', withAuth('absence_list', (s) => http.get(`${BASE}/api/absence/requests?size=20`, tag('absence_list', s.access)))); },
};

export default function () {
  sleep(Math.random() * 0.2);                        // 도착 시각을 k6 의 등간격 스케줄에서 살짝 흩는다
  ACTIONS[weighted(MIX)]();
}

// 이름별 p50/p95/p99·건수·실패율 표. 원 JSON 은 너무 커서 여기서 줄인다(journey.js 와 같은 꼴).
export function handleSummary(data) {
  const m = data.metrics;
  const row = (k) => {
    const d = m[`http_req_duration{name:${k}}`], f = m[`http_req_failed{name:${k}}`], n = m[`http_reqs{name:${k}}`];
    if (!d) return null;
    return { name: k, n: n ? n.values.count : null, rps: n ? +n.values.rate.toFixed(1) : null,
      p50: +d.values.med.toFixed(1), p95: +d.values['p(95)'].toFixed(1), p99: +d.values['p(99)'].toFixed(1), max: +d.values.max.toFixed(0),
      fail: f ? +(f.values.rate * 100).toFixed(2) : null };
  };
  const cnt = (k) => (m[k] ? m[k].values.count : 0);
  const out = {
    base: BASE, pattern: PATTERN, rate: RATE, jitter: PATTERN === 'random' ? JITTER : 0, seed: SEED, duration_s: DURATION_S,
    reqs: m.http_reqs.values.count, rps: +m.http_reqs.values.rate.toFixed(1),
    fail_pct: +(m.http_req_failed.values.rate * 100).toFixed(2),
    p95_all: +m.http_req_duration.values['p(95)'].toFixed(1),
    dropped: cnt('dropped_iterations'), max_vus: m.vus_max ? m.vus_max.values.value : null,
    status_4xx: cnt('status_4xx'), status_5xx: cnt('status_5xx'),
    login_fail: cnt('login_fail'), refresh_fail: cnt('refresh_fail'), relogin_401: cnt('relogin_401'), attendance_dup: cnt('attendance_dup'),
    by_name: NAMES.map(row).filter(Boolean).sort((a, b) => b.n - a.n),
  };
  const file = __ENV.OUT || 'traffic-summary.json';
  return { [file]: JSON.stringify(out, null, 1), stdout: textSummary(out) };
}
function textSummary(o) {
  const l = [`\n== traffic ${o.pattern} rate=${o.rate}/s(±${o.jitter * 100}%) seed=${o.seed} ${o.duration_s}s → reqs=${o.reqs} rps=${o.rps} fail=${o.fail_pct}% p95=${o.p95_all}ms dropped=${o.dropped} maxVUs=${o.max_vus}`,
    `   4xx=${o.status_4xx} 5xx=${o.status_5xx} login_fail=${o.login_fail} refresh_fail=${o.refresh_fail} relogin_401=${o.relogin_401} attendance_dup=${o.attendance_dup}`,
    'name                    n      rps     p50     p95     p99     max   fail%'];
  for (const r of o.by_name) l.push(`${r.name.padEnd(20)} ${String(r.n).padStart(7)} ${String(r.rps).padStart(7)} ${String(r.p50).padStart(7)} ${String(r.p95).padStart(7)} ${String(r.p99).padStart(7)} ${String(r.max).padStart(7)} ${String(r.fail).padStart(7)}`);
  return l.join('\n') + '\n';
}
