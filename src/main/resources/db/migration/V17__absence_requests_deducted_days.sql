-- 승인 취소(#104 잔여)가 돌려줄 연차 일수를 승인 때 기록한다.
--
-- 취소 시 근무일 수를 다시 세면 승인 뒤 캘린더가 바뀐 경우(공휴일 추가 등) 깎은 것과 다른 값을 돌려준다.
-- 환급은 재계산이 아니라 기록이어야 한다. 병가는 차감이 없어 NULL, V17 이전에 승인된 건도 NULL —
-- 그 건은 서비스가 승인 때와 같은 규칙으로 다시 센다(AbsenceRequestService.refundableDays).
ALTER TABLE absence_requests ADD COLUMN deducted_days integer;
