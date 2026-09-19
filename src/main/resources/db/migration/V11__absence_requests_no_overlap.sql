-- #104 — 같은 사용자의 승인된 휴가는 기간이 겹칠 수 없다.
--
-- 서비스가 신청 시·승인 시 두 번 검사하지만, 검사와 쓰기 사이는 트랜잭션이 다르다. 승인은 사용자 행을
-- FOR UPDATE로 잠그므로(LeaveBalanceConcurrencyTest) 실제로는 직렬화되지만, 그 락은 연차(VACATION)에만
-- 걸린다 — 병가 둘, 병가+연차는 락 없이 지나간다. 잔액이 아니라 "겹치는 승인이 둘"이 문제인 자리라
-- 잔액 락에 기대지 않고 제약으로 못 박는다.
--
-- daterange(start, end, '[]')는 양끝 포함. && 는 겹침. user_id WITH = 가 같은 사용자로 좁힌다.
-- 다른 컬럼 타입(varchar)을 gist에 같이 넣으려면 btree_gist가 필요하다 — pgvector/pgvector:pg17 이미지에 contrib로 들어 있다.
-- WHERE (status = 'APPROVED'): PENDING은 서비스가 막고(겹치는 PENDING 둘은 하나만 승인된다), REJECTED는 자리를 차지하지 않는다.
--
-- 기존 행 중 겹치는 APPROVED가 있으면 ADD CONSTRAINT가 실패한다. 그건 잔액이 두 번 깎인 데이터이므로
-- 조용히 넘기지 않고 사람이 보게 둔다 — 아래 DO 블록이 어떤 행인지 먼저 찍는다.
CREATE EXTENSION IF NOT EXISTS btree_gist;

DO $$
DECLARE
    dup record;
BEGIN
    FOR dup IN
        SELECT a.request_id, b.request_id AS other_id, a.user_id, a.start_date, a.end_date
        FROM absence_requests a
        JOIN absence_requests b
          ON a.user_id = b.user_id AND a.request_id < b.request_id
         AND daterange(a.start_date, a.end_date, '[]') && daterange(b.start_date, b.end_date, '[]')
        WHERE a.status = 'APPROVED' AND b.status = 'APPROVED'
    LOOP
        RAISE NOTICE '겹치는 승인 휴가: user_id=% request_id=% / % (%~%) — 잔액이 두 번 깎였을 수 있다',
            dup.user_id, dup.request_id, dup.other_id, dup.start_date, dup.end_date;
    END LOOP;
END $$;

ALTER TABLE absence_requests
    ADD CONSTRAINT absence_requests_no_overlap
    EXCLUDE USING gist (user_id WITH =, daterange(start_date, end_date, '[]') WITH &&)
    WHERE (status = 'APPROVED');
