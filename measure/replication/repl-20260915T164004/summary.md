| 항목 | 값 |
|---|---|
| DB 크기 | 183 MB (시드 23s) |
| pg_basebackup | 40s |
| 반영 지연(유휴, n=20) | p50 81.5 · p95 340.6 · max 340.6 ms |
| 반영 지연(부하 중 프로브, n=8) | p50 381.7 · p95 856.5 · max 856.5 ms |
| replay_lag(부하 중, pg_stat_replication, n=8) | p50 280.5 · max 991.3 ms |
| 부하 | 506000행 삽입, 끝난 뒤 따라잡기 47s |
| promote → 쓰기 가능 | 10826 ms |
