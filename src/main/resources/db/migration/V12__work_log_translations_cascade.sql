-- #101 — 번역이 한 번이라도 된 작업일지는 삭제가 500이었다.
--
-- work_log_translations.log_id의 FK가 NO ACTION(V2 베이스라인, Hibernate가 만든 이름)이라 DELETE /api/work-logs/{id}가
-- DataIntegrityViolationException으로 죽었다. 번역은 원본 없이 의미가 없으므로 원본을 따라 지운다 —
-- V6의 chat_message_translations가 같은 결정(ON DELETE CASCADE)이다. 엔티티 cascade 대신 DB에 두는 것도 같은 이유:
-- 번역 행을 하나씩 읽어 지울 이유가 없고, 어느 경로로 지우든 같아야 한다.
--
-- 이름을 못 박아 지우지 않는 것은, 이 FK 이름이 Hibernate 자동 생성이라 베이스라인 전 DB에서 다를 수 있어서다.
-- 대신 "work_log_translations에서 work_logs를 가리키는 FK"를 카탈로그에서 찾아 지운다.
DO $$
DECLARE
    fk text;
BEGIN
    SELECT conname INTO fk
    FROM pg_constraint
    WHERE conrelid = 'work_log_translations'::regclass
      AND confrelid = 'work_logs'::regclass
      AND contype = 'f';
    IF fk IS NOT NULL THEN
        EXECUTE format('ALTER TABLE work_log_translations DROP CONSTRAINT %I', fk);
    END IF;
END $$;

ALTER TABLE work_log_translations
    ADD CONSTRAINT work_log_translations_log_id_fkey
    FOREIGN KEY (log_id) REFERENCES work_logs(log_id) ON DELETE CASCADE;
