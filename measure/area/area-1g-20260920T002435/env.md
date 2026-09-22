# area denorm lab  2026-09-20T00:24:36+0900
image=pgvector/pgvector:pg17 mem=1g rows=1000000 rare_every=10000 users=500 write_rows=10000 passes="O A B"
host: MINGW64_NT-10.0-19045 3.6.5-22c95533.x86_64 x86_64
docker 29.1.2
source: 28d8dd9 (rig 커밋; 본 판은 스크립트 복사본으로 실행 — README 함정 1)
seed_ms=110086 vacuum_ms=2061 analyze_ms=83303 heap=477 MB
 ship_yard_area | users |  logs  
----------------+-------+--------
 A0             |    84 | 168000
 A1             |   250 | 500000
 A2             |   100 | 200000
 A3             |    50 | 100000
 A4             |    11 |  22000
 A5             |     5 |  10000
(6 rows)

cursor: created_at=2025-07-14 11:20:00 log_id=381571
