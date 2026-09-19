-- ADR-0011 5절 — 항목 퇴역.
--
-- 점검 기록이 있는 항목은 지울 수 없었다(409 CHECKLIST_ITEM_HAS_RESULTS). 문구 수정은 됐는데, 그러면 과거 체크가
-- "무엇에 대한 체크"였는지가 바뀐다. 둘 다 틀렸다 — 기록이 있는 항목은 지우지도 바꾸지도 않고 퇴역시킨다.
-- 퇴역한 항목은 그 뒤에 연 회차에 나오지 않고, 그 전에 연 회차는 그대로 참조한다(ChecklistItemRepository.findActiveAt).
ALTER TABLE checklist_items ADD COLUMN retired_at timestamp(6) without time zone;

-- 자식 FK 컬럼 인덱스. 항목 조회는 전부 checklist_id로 시작하는데(회차 조회마다 한 번) 인덱스가 없었다 —
-- V3·V4·V11이 다른 자식 테이블에 만든 것과 같은 이유. (checklist_id, sequence)로 두어 ORDER BY까지 받는다.
CREATE INDEX IF NOT EXISTS idx_checklist_items_checklist ON checklist_items (checklist_id, sequence);
