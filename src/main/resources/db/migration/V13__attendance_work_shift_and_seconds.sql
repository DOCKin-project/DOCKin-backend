-- ADR-0010 — 근무일을 벽시계 날짜에서 교대 기준일로 (#98).
--
-- 무엇이 깨져 있었나
--   work_date = LocalDate.now()였다. 야간조(22:00~06:00)는 D일 22:00에 출근 행이 work_date = D로 생기고,
--   D+1일 06:00 퇴근은 work_date = D+1을 찾아 "출근 기록 없음"이 됐다. 야간조는 퇴근을 찍을 수 없었다.
--   지각도 시각만 비교해(00:30 > 22:00 = false) 야간조의 새벽 출근이 정상 출근이었다.
--
-- 코드는 근무일을 WorkDay(교대의 dayBoundary 이전이면 전날)로 계산하고, 퇴근은 날짜가 아니라
-- "열린 기록"을 닫는다. 이 마이그레이션은 그 코드가 서는 자리를 만든다 — 셋이다.

-- ---------------------------------------------------------------- 1. 판정 교대 스냅샷

-- users.work_shift는 바뀔 수 있다. 석 달 전 행이 어느 교대 기준으로 지각인지는 그때의 교대가 답이므로
-- 행에 남긴다. 기존 행은 사용자의 현재 교대로 채운다 — 근사지만 지금 있는 정보 중 가장 가깝고,
-- NULL(V2에서 users.work_shift가 nullable)이면 코드의 기본값과 같은 MORNING이다.
ALTER TABLE attendance ADD COLUMN work_shift varchar(20);

UPDATE attendance a
   SET work_shift = COALESCE(u.work_shift, 'MORNING')
  FROM users u
 WHERE u.user_id = a.user_id;

ALTER TABLE attendance ALTER COLUMN work_shift SET NOT NULL;
ALTER TABLE attendance ADD CONSTRAINT attendance_work_shift_check
    CHECK (work_shift IN ('MORNING', 'AFTERNOON', 'NIGHT'));

-- ---------------------------------------------------------------- 2. 근무 시간을 초로

-- total_work_time은 varchar였다. 서비스는 "HH:mm:ss"를, 시드는 "9h12m"을 넣었다 — 같은 컬럼에 표기가 둘이고
-- 어느 쪽도 SUM이 안 된다. 문자열을 파싱하지 않고 출퇴근 시각의 차로 다시 계산한다. 그것이 원천이고,
-- 문자열은 그 차를 표시용으로 적은 것이었다.
ALTER TABLE attendance ADD COLUMN work_seconds integer;

UPDATE attendance
   SET work_seconds = EXTRACT(EPOCH FROM (clock_out_time - clock_in_time))::integer
 WHERE clock_in_time IS NOT NULL
   AND clock_out_time IS NOT NULL;

ALTER TABLE attendance DROP COLUMN total_work_time;

-- ---------------------------------------------------------------- 3. 야간조 기존 행의 근무일 재배정

-- 새 규칙에서 야간조의 정오 이전 이벤트는 전날 근무일이다. 기존 행 중 야간조가 00:00~11:59에 출근한 것은
-- 예전 규칙으로 그날에 적혔으므로 하루 당긴다. 22:00 출근(대부분)은 정오 이후라 그대로다.
--
-- 당긴 결과가 같은 사람의 다른 행과 (user_id, work_date)에서 겹치면 멈춘다. 그 두 행은 "같은 근무일에 두 번 출근"이라
-- 데이터가 규칙을 반증하는 것이고, 어느 쪽이 맞는지는 사람이 봐야 한다(V5·V3과 같은 장치).
-- 겹침 상대가 자기도 옮겨지는 행이면 겹침이 아니다 — 연속 야간에서 이틀 다 자정 넘어 출근한 경우가 그렇다.
--
-- UPDATE는 한 문장으로 하지 않는다. 유니크 제약은 행마다 즉시 검사되므로 D+2 → D+1을 옮길 때 D+1 행이 아직
-- 안 옮겨졌으면 거기서 실패한다. 근무일 오름차순으로 한 행씩 옮기면 앞 행이 먼저 비켜 준다.
DO $$
DECLARE
    collisions bigint;
    r record;
BEGIN
    SELECT count(*) INTO collisions
      FROM attendance a
      JOIN attendance b
        ON b.user_id = a.user_id
       AND b.work_date = a.work_date - 1
     WHERE a.work_shift = 'NIGHT'
       AND a.clock_in_time IS NOT NULL
       AND a.clock_in_time::time < TIME '12:00'
       AND NOT (b.work_shift = 'NIGHT'
                AND b.clock_in_time IS NOT NULL
                AND b.clock_in_time::time < TIME '12:00');

    IF collisions > 0 THEN
        RAISE EXCEPTION
            '야간조의 정오 이전 출근 행을 전날 근무일로 옮기면 기존 행과 겹치는 것이 % 건 있다. '
            '같은 근무일에 행이 둘이라는 뜻이니 어느 쪽을 남길지 정한 뒤 다시 실행하라.', collisions;
    END IF;

    FOR r IN
        SELECT id
          FROM attendance
         WHERE work_shift = 'NIGHT'
           AND clock_in_time IS NOT NULL
           AND clock_in_time::time < TIME '12:00'
         ORDER BY user_id, work_date
    LOOP
        UPDATE attendance SET work_date = work_date - 1 WHERE id = r.id;
    END LOOP;
END $$;

-- ---------------------------------------------------------------- 4. 열린 기록 조회

-- 퇴근은 "출근은 있고 퇴근은 없는 가장 최근 행"을 찾는다(AttendanceRepository). 사용자당 그런 행은 0~1개라
-- 부분 인덱스면 행이 거의 없고, 조건이 쿼리와 정확히 같아야 플래너가 탄다.
CREATE INDEX IF NOT EXISTS idx_attendance_open
    ON attendance (user_id, clock_in_time DESC)
 WHERE clock_out_time IS NULL AND clock_in_time IS NOT NULL;
