-- B L1_A0
SELECT w.* FROM work_logs w WHERE w.ship_yard_area = 'A0' AND (CAST(NULL AS varchar) IS NULL OR w.status = NULL) AND (CAST(NULL AS timestamp) IS NULL OR (w.created_at <= NULL AND (w.created_at < NULL OR w.log_id < NULL))) ORDER BY w.created_at DESC, w.log_id DESC LIMIT 21

-- B L2_A0
SELECT w.* FROM work_logs w WHERE w.ship_yard_area = 'A0' AND (CAST(NULL AS varchar) IS NULL OR w.status = NULL) AND (w.created_at <= '2025-07-14 11:20:00' AND (w.created_at < '2025-07-14 11:20:00' OR w.log_id < 381571)) ORDER BY w.created_at DESC, w.log_id DESC LIMIT 21

-- B S1_A0
SELECT w.* FROM work_logs w WHERE w.ship_yard_area = 'A0' AND (w.title LIKE '%크랭크축%' OR w.log_text LIKE '%크랭크축%') AND (CAST(NULL AS timestamp) IS NULL OR (w.created_at <= NULL AND (w.created_at < NULL OR w.log_id < NULL))) ORDER BY w.created_at DESC, w.log_id DESC LIMIT 21

-- B S2_A0
SELECT w.* FROM work_logs w WHERE w.ship_yard_area = 'A0' AND (w.title LIKE '%베어링%' OR w.log_text LIKE '%베어링%') AND (CAST(NULL AS timestamp) IS NULL OR (w.created_at <= NULL AND (w.created_at < NULL OR w.log_id < NULL))) ORDER BY w.created_at DESC, w.log_id DESC LIMIT 21

-- B L1_A1
SELECT w.* FROM work_logs w WHERE w.ship_yard_area = 'A1' AND (CAST(NULL AS varchar) IS NULL OR w.status = NULL) AND (CAST(NULL AS timestamp) IS NULL OR (w.created_at <= NULL AND (w.created_at < NULL OR w.log_id < NULL))) ORDER BY w.created_at DESC, w.log_id DESC LIMIT 21

-- B L2_A1
SELECT w.* FROM work_logs w WHERE w.ship_yard_area = 'A1' AND (CAST(NULL AS varchar) IS NULL OR w.status = NULL) AND (w.created_at <= '2025-07-14 11:20:00' AND (w.created_at < '2025-07-14 11:20:00' OR w.log_id < 381571)) ORDER BY w.created_at DESC, w.log_id DESC LIMIT 21

-- B S1_A1
SELECT w.* FROM work_logs w WHERE w.ship_yard_area = 'A1' AND (w.title LIKE '%크랭크축%' OR w.log_text LIKE '%크랭크축%') AND (CAST(NULL AS timestamp) IS NULL OR (w.created_at <= NULL AND (w.created_at < NULL OR w.log_id < NULL))) ORDER BY w.created_at DESC, w.log_id DESC LIMIT 21

-- B S2_A1
SELECT w.* FROM work_logs w WHERE w.ship_yard_area = 'A1' AND (w.title LIKE '%베어링%' OR w.log_text LIKE '%베어링%') AND (CAST(NULL AS timestamp) IS NULL OR (w.created_at <= NULL AND (w.created_at < NULL OR w.log_id < NULL))) ORDER BY w.created_at DESC, w.log_id DESC LIMIT 21

-- B L1_A5
SELECT w.* FROM work_logs w WHERE w.ship_yard_area = 'A5' AND (CAST(NULL AS varchar) IS NULL OR w.status = NULL) AND (CAST(NULL AS timestamp) IS NULL OR (w.created_at <= NULL AND (w.created_at < NULL OR w.log_id < NULL))) ORDER BY w.created_at DESC, w.log_id DESC LIMIT 21

-- B L2_A5
SELECT w.* FROM work_logs w WHERE w.ship_yard_area = 'A5' AND (CAST(NULL AS varchar) IS NULL OR w.status = NULL) AND (w.created_at <= '2025-07-14 11:20:00' AND (w.created_at < '2025-07-14 11:20:00' OR w.log_id < 381571)) ORDER BY w.created_at DESC, w.log_id DESC LIMIT 21

-- B S1_A5
SELECT w.* FROM work_logs w WHERE w.ship_yard_area = 'A5' AND (w.title LIKE '%크랭크축%' OR w.log_text LIKE '%크랭크축%') AND (CAST(NULL AS timestamp) IS NULL OR (w.created_at <= NULL AND (w.created_at < NULL OR w.log_id < NULL))) ORDER BY w.created_at DESC, w.log_id DESC LIMIT 21

-- B S2_A5
SELECT w.* FROM work_logs w WHERE w.ship_yard_area = 'A5' AND (w.title LIKE '%베어링%' OR w.log_text LIKE '%베어링%') AND (CAST(NULL AS timestamp) IS NULL OR (w.created_at <= NULL AND (w.created_at < NULL OR w.log_id < NULL))) ORDER BY w.created_at DESC, w.log_id DESC LIMIT 21

