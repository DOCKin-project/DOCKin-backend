-- P2-15-6 — work_logs.user_id를 NOT NULL로 만든다.
--
-- P2-15-2에서 갈라져 나온 항목이다. 그때 equipment_id가 NULL이면 목록 페이지 전체가
-- 500이 되는 것을 고치면서, 바로 윗줄의 getMember().getUserId()도 같은 자리에 있다는 것을
-- 확인했다. 그런데 고칠 방향이 반대였다.
--
--   equipmentId  계약이 선택 필드(requiredMode 없음)     -> null이 정당한 값. DTO에 가드를 넣었다
--   userId       계약이 requiredMode = REQUIRED         -> 가드를 넣으면 필수 필드에 null을 담은
--                                                          응답을 조용히 내보내게 된다
--
-- 후자의 답은 가드가 아니라 컬럼을 NOT NULL로 만드는 것이고, 그게 이 마이그레이션이다.
-- 그러면 WorkLogDto.from의 그 줄은 검사할 필요가 없어진다 -- 방어 코드를 넣는 대신
-- 방어할 상태 자체를 없앤다.
--
-- 데이터가 이 판단을 지지한다. 로컬 DB에서 두 컬럼은 스키마상 똑같이 nullable인데
-- 실제 값은 정반대다.
--
--   work_logs 20,016행 중  user_id NULL       0건
--                          equipment_id NULL  3,331건
--
-- 즉 "장비 없는 작업일지"는 실재하고 "작성자 없는 작업일지"는 실재한 적이 없다.
-- 코드도 같은 말을 한다 -- createWorklog와 createSttWorklog 둘 다 member를 필수로 요구하고,
-- users를 지우려 해도 FK가 NO ACTION이라 막히므로 user_id가 나중에 NULL이 되는 경로도 없다.
--
-- 참고로 work_log_comments.user_id는 이미 NOT NULL이다(V2). 댓글에는 작성자를 요구하면서
-- 작업일지에는 요구하지 않고 있었다 -- 같은 도메인 안에서 어긋나 있던 것을 맞춘다.

-- 행이 있으면 바꾸지 않고 실패시킨다.
--
-- SET NOT NULL은 위반 행이 하나라도 있으면 PostgreSQL이 알아서 거부하지만, 그때 나오는
-- 메시지는 "column contains null values"뿐이라 무엇을 어떻게 해야 하는지가 없다.
-- 여기서 먼저 세고 멈추면 몇 건인지와 판단 근거를 함께 남길 수 있다.
-- V3의 log_images DROP과 같은 장치다.
DO $$
DECLARE
    orphans bigint;
BEGIN
    SELECT count(*) INTO orphans FROM work_logs WHERE user_id IS NULL;

    IF orphans > 0 THEN
        RAISE EXCEPTION
            'work_logs에 user_id가 NULL인 행이 % 건 있다. "작성자 없는 작업일지는 실재하지 않는다"는 '
            '전제로 NOT NULL을 걸려 했으나, 데이터가 그 전제를 반증한다. '
            '그 행들이 무엇인지 확인하고 (1) 작성자를 채울 것인지 (2) 지울 것인지 '
            '(3) NULL을 허용하고 대신 WorkLogDto.from에 가드를 넣을 것인지 정한 뒤 다시 실행하라.', orphans;
    END IF;
END $$;

ALTER TABLE work_logs ALTER COLUMN user_id SET NOT NULL;
