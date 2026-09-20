// 채팅 팬아웃 (measure/k6-load, ADR-0008 M5 / SERVICE-SCALE-ASSUMPTIONS 3-3).
//
//   VU 한 명 = 앱을 켜 두고 채팅방 하나에 들어가 있는 근로자. 로그인(HTTP) → /ws STOMP CONNECT →
//   자기 방(/sub/chat/room/{room})·자기 방 목록(/sub/user/{uid}/rooms) 구독 → SEND_EVERY 초마다 메시지 하나.
//   방은 10명씩 고정(seed-chat-rooms.sql): VU n = 계정 k6u{n}, 방 = 1000 + ceil(n/10).
//
//   서버가 하는 일은 메시지 하나에 저장 1 + 방 브로드캐스트 10 + 멤버별 /rooms 10 = 프레임 20개.
//   인바운드 = SESSIONS / SEND_EVERY (msg/s), 팬아웃 = 그 20배 (push/s). 가정 피크는 인바운드 30, 팬아웃 300.
//
//   재는 것: fanout_ms = 방 프레임을 받은 시각 − 보낸 시각(본문에 박은 ts, 부하기 한 대라 같은 시계).
//            recv_room / msg_sent 가 10이면 유실 0. connect_ms 는 CONNECT → CONNECTED.
//
//   SESSIONS=1000 SEND_EVERY=33 RAMP=1m HOLD=2m   (30 msg/s)
//   SESSIONS=1000 SEND_EVERY=3.3                   (300 msg/s = 가정의 10배)
//   SESSIONS=1000 SEND_EVERY=0                     (보내지 않음 — 세션만, 메모리·heartbeat)
import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://127.0.0.1';
const WS_BASE = BASE.replace(/^http/, 'ws');
const SESSIONS = Number(__ENV.SESSIONS || 1000);
const SEND_EVERY = Number(__ENV.SEND_EVERY || 33);   // 초. 0이면 안 보낸다
const RAMP = __ENV.RAMP || '1m';
const HOLD = __ENV.HOLD || '2m';
const ROOM_BASE = Number(__ENV.ROOM_BASE || 1000);
const ROOM_SIZE = Number(__ENV.ROOM_SIZE || 10);

const msgSent = new Counter('msg_sent');
const recvRoom = new Counter('recv_room');
const recvRooms = new Counter('recv_rooms');
const recvError = new Counter('recv_error');
const wsError = new Counter('ws_error');
const fanout = new Trend('fanout_ms', true);
const connectMs = new Trend('connect_ms', true);          // 소켓 열기 → CONNECTED (전체)
const handshakeMs = new Trend('handshake_ms', true);      // 소켓 열기 → 업그레이드 완료(open)
const stompMs = new Trend('stomp_connect_ms', true);      // open → CONNECTED (서버의 CONNECT 처리)

function dur(s) { const m = /^(\d+)(ms|s|m)$/.exec(s); return Number(m[1]) * ({ ms: 1, s: 1000, m: 60000 })[m[2]]; }
const RAMP_MS = dur(RAMP), HOLD_MS = dur(HOLD);

export const options = {
  scenarios: {
    chat: {
      executor: 'ramping-vus', startVUs: 0,
      stages: [{ duration: RAMP, target: SESSIONS }, { duration: HOLD, target: SESSIONS }, { duration: '10s', target: 0 }],
      gracefulRampDown: '20s', gracefulStop: '20s',
    },
  },
  thresholds: { 'fanout_ms': ['p(95)<1000'], 'ws_error': ['count<1'] },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

const NUL = String.fromCharCode(0);
function frame(cmd, headers, body) {
  const h = Object.entries(headers).map(([k, v]) => k + ':' + v + '\n').join('');
  return cmd + '\n' + h + '\n' + (body || '') + NUL;   // 헤더가 없으면 빈 줄 하나만 — 둘이면 서버가 파싱 실패(c1000-30의 DISCONNECT)
}
function parse(raw) {
  if (raw === '\n' || raw === '') return null;               // heartbeat
  const end = raw.indexOf('\n\n');
  const head = raw.slice(0, end).split('\n');
  const headers = {};
  for (const h of head.slice(1)) { const i = h.indexOf(':'); if (i > 0) headers[h.slice(0, i)] = h.slice(i + 1); }
  let body = raw.slice(end + 2);
  if (body.endsWith(NUL)) body = body.slice(0, -1);
  return { cmd: head[0], headers, body };
}

export default function () {
  const n = exec.vu.idInTest;                               // 1..SESSIONS
  const uid = 'k6u' + String(n).padStart(5, '0');
  const room = ROOM_BASE + Math.ceil(n / ROOM_SIZE);
  const endAt = exec.scenario.startTime + RAMP_MS + HOLD_MS;

  const r = http.post(`${BASE}/api/member/login`, JSON.stringify({ userId: uid, password: 'dockin1234' }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
  if (!check(r, { 'login 200': (x) => x.status === 200 })) { wsError.add(1); return; }
  const token = r.json('accessToken');

  const t0 = Date.now();
  const res = ws.connect(`${WS_BASE}/ws`, { tags: { name: 'ws' } }, function (socket) {
    let connected = false;
    let tOpen = 0;
    socket.on('open', () => {
      tOpen = Date.now(); handshakeMs.add(tOpen - t0);
      socket.send(frame('CONNECT', { 'accept-version': '1.2', 'heart-beat': '10000,10000', token: 'Bearer ' + token }));
    });
    socket.on('message', (raw) => {
      const f = parse(raw);
      if (!f) return;
      if (f.cmd === 'CONNECTED') {
        connected = true; connectMs.add(Date.now() - t0); stompMs.add(Date.now() - tOpen);
        socket.send(frame('SUBSCRIBE', { id: 'sub-room', destination: `/sub/chat/room/${room}` }));
        socket.send(frame('SUBSCRIBE', { id: 'sub-rooms', destination: `/sub/user/${uid}/rooms` }));
        socket.send(frame('SUBSCRIBE', { id: 'sub-err', destination: `/sub/user/${uid}/errors` }));
        // 클라이언트 heartbeat. 서버가 10초를 기대하니 그 절반마다 한 줄.
        socket.setInterval(() => socket.send('\n'), 5000);
        if (SEND_EVERY > 0) {
          // 램프가 끝난 뒤에 보내기 시작한다 — 램프 중에 보내면 아직 안 붙은 멤버만큼 수신이 빠져 유실과
          // 구분이 안 된다(스모크에서 room/msg 8.89). 위상은 흩는다 — 전원이 같은 순간에 보내면 스파이크다.
          const rampEnd = exec.scenario.startTime + RAMP_MS;
          socket.setTimeout(() => {
            const send = () => {
              const ts = Date.now();
              if (ts > endAt - 3000) return;                 // 끝 3초는 안 보낸다 — 닫히는 소켓이 못 받는 걸 유실로 세지 않게
              socket.send(frame('SEND', { destination: '/pub/chat/message', 'content-type': 'application/json' },
                JSON.stringify({ roomId: room, senderId: uid, content: 'ts=' + ts + ' 팬아웃 측정', messageType: 'TEXT' })));
              msgSent.add(1);
            };
            send();
            socket.setInterval(send, SEND_EVERY * 1000);
          }, Math.max(0, rampEnd - Date.now()) + Math.random() * SEND_EVERY * 1000);
        }
        // 단계 끝까지 붙어 있다가 곱게 끊는다.
        socket.setTimeout(() => { socket.send(frame('DISCONNECT', {})); socket.close(); }, Math.max(1000, endAt - Date.now()));
      } else if (f.cmd === 'MESSAGE') {
        const d = f.headers.destination || '';
        if (d.startsWith('/sub/chat/room/')) {
          recvRoom.add(1);
          const m = /ts=(\d+)/.exec(f.body);
          if (m) fanout.add(Date.now() - Number(m[1]));
        } else if (d.endsWith('/rooms')) recvRooms.add(1);
        else if (d.endsWith('/errors')) recvError.add(1);
      } else if (f.cmd === 'ERROR') {
        wsError.add(1);
      }
    });
    socket.on('error', () => { wsError.add(1); });
  });
  if (!(res && res.status === 101)) wsError.add(1);
  // 반복을 여기서 끝내면 ramping-vus 가 곧장 다음 반복(로그인부터)을 시작한다 — 1,000 VU 가 동시에 bcrypt 를
  // 치고 풀을 비운다(c1000-30 끝에 app 790%·pending 189). 단계가 끝날 때까지 잔다.
  const left = endAt + 15000 - Date.now();
  if (left > 0) sleep(left / 1000);
}

export function handleSummary(data) {
  const m = data.metrics;
  const cnt = (k) => (m[k] && m[k].values && m[k].values.count) || 0;
  const tr = (k) => (m[k] && m[k].values) || {};
  const out = {
    sessions: SESSIONS, send_every_s: SEND_EVERY, ramp: RAMP, hold: HOLD,
    msg_sent: cnt('msg_sent'), recv_room: cnt('recv_room'), recv_rooms: cnt('recv_rooms'), recv_error: cnt('recv_error'), ws_error: cnt('ws_error'),
    room_per_msg: cnt('msg_sent') ? +(cnt('recv_room') / cnt('msg_sent')).toFixed(2) : null,
    rooms_per_msg: cnt('msg_sent') ? +(cnt('recv_rooms') / cnt('msg_sent')).toFixed(2) : null,
    fanout: { p50: tr('fanout_ms').med, p95: tr('fanout_ms')['p(95)'], p99: tr('fanout_ms')['p(99)'], max: tr('fanout_ms').max },
    connect: { p50: tr('connect_ms').med, p95: tr('connect_ms')['p(95)'], max: tr('connect_ms').max },
    handshake: { p50: tr('handshake_ms').med, p95: tr('handshake_ms')['p(95)'], max: tr('handshake_ms').max },
    stomp_connect: { p50: tr('stomp_connect_ms').med, p95: tr('stomp_connect_ms')['p(95)'], max: tr('stomp_connect_ms').max },
  };
  const line = `== chat sessions=${SESSIONS} every=${SEND_EVERY}s sent=${out.msg_sent} room/msg=${out.room_per_msg} rooms/msg=${out.rooms_per_msg} fanout p50=${out.fanout.p50}ms p95=${out.fanout.p95}ms p99=${out.fanout.p99}ms max=${out.fanout.max}ms connect p95=${out.connect.p95}ms (handshake ${out.handshake.p95} / stomp ${out.stomp_connect.p95}) ws_error=${out.ws_error} recv_error=${out.recv_error}\n`;
  const r = { stdout: line };
  if (__ENV.OUT) r[__ENV.OUT] = JSON.stringify(out);
  return r;
}
