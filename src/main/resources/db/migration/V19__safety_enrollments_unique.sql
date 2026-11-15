-- V19: safety_enrollments에 (user_id, course_id) 유니크, course_id NOT NULL (#141)
--
-- 같은 사용자가 같은 교육 완료를 동시에 두 번 보내면 행이 둘 생겼다(찾고-없으면-만들기 사이의 경합).
-- 그 뒤 단건 조회(findByUserIdUserIdAndCourseIdCourseId, Optional)가 IncorrectResultSizeDataAccessException으로 500.
-- 이제 완료는 INSERT … ON CONFLICT 한 문장이고(SafetyEnrollmentRepository.upsertWatched), 이 제약이 그 충돌 대상이다.

-- 1. course_id가 NULL인 행은 지우지 않고 멈춘다. 코스 없는 수강 기록은 만들어지는 경로가 없어 원인을 봐야 한다(V5·V16과 같은 장치).
DO $$
DECLARE
    bad bigint;
BEGIN
    SELECT count(*) INTO bad FROM safety_enrollments WHERE course_id IS NULL;
    IF bad > 0 THEN
        RAISE EXCEPTION 'safety_enrollments에 course_id가 NULL인 행이 % 건 있다. 어느 교육의 기록인지 확인하고 고치거나 지운 뒤 다시 실행하라.', bad;
    END IF;
END $$;

-- 2. 중복 정리. 같은 (user_id, course_id)는 같은 사실(그 사람이 그 교육을 봤다)이라 하나만 남긴다.
--    남길 행: WATCHED가 있으면 그중 완료일이 가장 늦은 것, 없으면 가장 최근 행. 지운 수는 NOTICE로 남긴다.
DO $$
DECLARE
    removed bigint;
BEGIN
    WITH ranked AS (
        SELECT enrollment_id,
               row_number() OVER (
                   PARTITION BY user_id, course_id
                   ORDER BY (status = 'WATCHED') DESC, completion_date DESC NULLS LAST, enrollment_id DESC
               ) AS rn
        FROM safety_enrollments
    )
    DELETE FROM safety_enrollments e
    USING ranked r
    WHERE e.enrollment_id = r.enrollment_id AND r.rn > 1;
    GET DIAGNOSTICS removed = ROW_COUNT;
    RAISE NOTICE 'safety_enrollments 중복 % 행 정리', removed;
END $$;

-- 3. 제약.
ALTER TABLE safety_enrollments ALTER COLUMN course_id SET NOT NULL;
ALTER TABLE safety_enrollments DROP CONSTRAINT IF EXISTS uk_safety_enrollment_user_course;
ALTER TABLE safety_enrollments ADD CONSTRAINT uk_safety_enrollment_user_course UNIQUE (user_id, course_id);
