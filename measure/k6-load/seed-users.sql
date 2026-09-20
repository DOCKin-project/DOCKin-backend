-- k6 부하 테스트용 계정 풀 (measure/k6-load). 시드(R__seed_sample.sql)가 먼저 적용된 DB 위에 넣는다.
--
-- 비밀번호는 시드와 같은 dockin1234 의 bcrypt 해시다. 해시 하나를 재사용하는 이유는 bcrypt 생성이
-- 건당 수십 ms라 4만 건을 새로 만들면 그것만 몇십 분이기 때문이고, 로그인 경로에서 서버가 하는 일
-- (bcrypt 대조)은 해시가 같든 다르든 동일하다.
--
-- 계정 수 40,000 = DAU 4,000의 10배. 출근은 1인 1일 1회라(uk_attendance_user_workdate) 한 단계에서
-- 4,000명을 넘게 밀면 같은 계정이 두 번 출근하게 된다. 그 두 번째는 INSERT가 아니라 "이미 출근" 분기라
-- 비용이 다르다. 풀을 크게 두고 단계 사이에 attendance를 비워서(reset-attendance.sql) 매 반복이
-- 첫 출근이 되게 한다.
--
-- 작업일지는 계정당 3건. GET /api/work-logs 가 빈 페이지를 돌려주면 그 요청은 측정 대상이 못 된다.
\set ON_ERROR_STOP on
\timing on

INSERT INTO users (user_id, name, password, role, language_code, tts_enabled,
                   created_at, ship_yard_area, work_shift, remaining_leave_days)
SELECT 'k6u' || lpad(g::text, 5, '0'),
       '부하' || (g % 1000),
       '$2a$10$PiimjtEaM74GXkb9cTBvH.QZQSVSsMpwskriTfMIqP2ORzdCKgyLm',
       'USER',
       (ARRAY['ko','vi','ko','ko'])[1 + g % 4],
       false,
       TIMESTAMP '2026-01-05 09:00:00',
       (ARRAY['1도크 선각공장','2도크 의장공장','3도크 도장공장'])[1 + g % 3],
       (ARRAY['MORNING','MORNING','AFTERNOON','NIGHT'])[1 + g % 4],
       15
FROM generate_series(1, 40000) g
ON CONFLICT (user_id) DO NOTHING;

-- created_at 은 90일 안에서 무작위다. 밤 15의 시드는 (g % 15) 로 날짜를 정했는데 g % 3 이 구역이라
-- 날짜와 구역이 상관돼 있었고, 그 분포에서는 V10 (created_at, log_id) 인덱스를 순서대로 걷는 목록 쿼리가
-- 실제보다 나쁘게 나온다. 밤 17부터 무작위로 흩는다(그 밤은 서버에서 손으로 바꿨고 여기 반영한 건 밤 18).
INSERT INTO work_logs (title, log_text, audio_file_url, created_at, updated_at, user_id, equipment_id)
SELECT '부하 테스트 작업일지 ' || i,
       repeat('용접 와이어 송급 상태를 확인하고 롤러 압력을 재조정했다. ', 6),
       NULL,
       ts, ts,
       'k6u' || lpad(g::text, 5, '0'),
       1 + (g % 3)
FROM generate_series(1, 40000) g, generate_series(1, 3) i,
     LATERAL (SELECT TIMESTAMP '2026-06-20 00:00:00' + random() * INTERVAL '90 days') t(ts)
WHERE NOT EXISTS (SELECT 1 FROM work_logs w WHERE w.user_id = 'k6u' || lpad(g::text, 5, '0'));

ANALYZE users; ANALYZE work_logs;
SELECT (SELECT count(*) FROM users) AS users, (SELECT count(*) FROM work_logs) AS work_logs;
