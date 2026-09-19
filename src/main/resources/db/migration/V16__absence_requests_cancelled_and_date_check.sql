-- #104 잔여 — 휴가 신청 취소(CANCELLED)와 날짜 순서 CHECK.
--
-- 1. status에 CANCELLED를 허용한다. V2의 CHECK가 세 값만 알아서 그대로면 INSERT/UPDATE가 실패한다.
--    CANCELLED는 REJECTED와 같은 급의 종결 상태다 — 행은 남고, 겹침(V11 EXCLUDE는 APPROVED만)에서 자리를 차지하지 않는다.
ALTER TABLE absence_requests DROP CONSTRAINT IF EXISTS absence_requests_status_check;
ALTER TABLE absence_requests ADD CONSTRAINT absence_requests_status_check
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'));

-- 2. 종료일 >= 시작일. 서비스만 검사하고 있었다(createRequest). #128이 "별도 PR"로 남긴 것.
--    위반 행이 있으면 ADD CONSTRAINT가 거부하는데 그 메시지엔 어느 행인지가 없다 — 먼저 세고 멈춘다(V5와 같은 장치).
DO $$
DECLARE
    bad bigint;
BEGIN
    SELECT count(*) INTO bad FROM absence_requests WHERE end_date < start_date;
    IF bad > 0 THEN
        RAISE EXCEPTION 'absence_requests에 end_date < start_date인 행이 % 건 있다. 서비스 검사를 우회해 들어온 데이터다 — 어느 쪽 날짜가 맞는지 확인하고 고친 뒤 다시 실행하라.', bad;
    END IF;
END $$;

ALTER TABLE absence_requests ADD CONSTRAINT absence_requests_date_order_check
    CHECK (end_date >= start_date);
