#!/usr/bin/env bash
#
# #91 + #118: 작업일지 목록·검색의 "구역 필터" 자리를 세 꼴로 나란히 잰다.
#   O = #127 이전  — users 를 통째로 올려 user_id IN (…)           + idx_work_logs_user 만 (V4)
#   A = #127 현재  — users 조인 WHERE u.ship_yard_area = :area      + V10 (created_at DESC, log_id DESC)
#   B = #91 제안   — work_logs.ship_yard_area 비정규화 컬럼          + (ship_yard_area, created_at DESC, log_id DESC)  (V10 도 그대로 둔다)
# (docs/DB-IMPROVEMENT-PLAN.md 4절 D1 "안 붙인다"의 후속, PG-BOOK-EXPERIMENTS.md 카드 ④의 다음 질문)
#
# [실행]
#   ./scripts/db/area-denorm-lab.sh                            # 본 판: 100만 행, 패스 O A B A B  → measure/area/area-<ts>/
#   ROWS=50000 PASSES="O A B" ./scripts/db/area-denorm-lab.sh  # 연기 시험
#
# [무엇을 하나]
#   trgm-lab 과 같은 일회용 컨테이너(운영 이미지·512MB), 같은 시드(어휘·행 길이·500명·1/10,000 희귀 문장).
#   [주의] 희귀 문장은 i % RARE_EVERY = 0 인 행에만 있고 RARE_EVERY(10,000)가 USERS(500)의 배수라 그 행의 작성자는 전부
#   bch0000(A0)이다. 그래서 A1·A5 의 "희귀" 검색은 결과 0건인 검색이 됐다 — 의도한 건 아니지만 그게 가장 중요한 줄이 됐다
#   (README 발견 1, #154). 시드를 바꾸면 그 줄과 비교가 안 되니 바꾸지 않는다. 결과 있는 희귀 검색을 재려면 RARE_EVERY 를 500 의 배수가
#   아닌 값(예: 10,007)으로 주고 새 디렉터리로 잰다.
#   다른 점 하나: 구역 크기를 일부러 고르지 않게 한다. #127 은 구역이 클수록(선택도가 낮을수록) V10 이 일찍 멈춘다고
#   했는데, 그 말을 뒤집으면 **작은 구역**이 V10 의 약점이다 — 최신부터 걷다 그 구역 행을 21개 만날 때까지 남의 행을
#   다 읽고 users 를 매 행 찾는다. 비정규화 인덱스는 그 구역 행만 걷는다. 그래서 세 구역을 잰다:
#     A0 = 84명(17%, i%6==0 — trgm-lab 과 같은 사용자라 그때의 240ms 와 잇는다)
#     A1 = 250명(50%, #118 의 "구역 3개 중 하나"에 가깝다)
#     A5 = 5명(1%, 작은 구역·신설 구역)
#   패스마다: pg_prewarm → 쿼리 12개(구역 3 × {목록 첫 페이지, 목록 중간 커서, 검색 희귀 '크랭크축', 검색 흔함 '베어링'})
#            × 7회(1·2회 워밍업) → EXPLAIN (ANALYZE, BUFFERS) → 쓰기 비용 50,000행 INSERT × 3.
#   B 첫 패스에서 컬럼 추가 + 백필 UPDATE + 인덱스 빌드 시간을 따로 적는다 — 이관 비용이다.
#   O 는 한 번만(기준선). A·B 는 A B A B 로 같은 조건 두 패스가 통제군이다.
#
# [절대 시간을 옮겨 적지 말 것]  이 호스트 값이다. 볼 것은 A↔B 의 배수, 구역 크기에 따른 기울기, 실행계획의 노드.
#
set -euo pipefail
cd "$(dirname "$0")/../.."

IMAGE=${IMAGE:-pgvector/pgvector:pg17}
MEM=${MEM:-512m}
ROWS=${ROWS:-1000000}
RARE_EVERY=${RARE_EVERY:-10000}
USERS=${USERS:-500}
WRITE_ROWS=${WRITE_ROWS:-50000}
PASSES=${PASSES:-"O A B A B"}
STAMP=$(date +%Y%m%dT%H%M%S)
C=${C:-area-lab-$STAMP}
OUT_DIR=${OUT_DIR:-measure/area/area-$STAMP}

mkdir -p "$OUT_DIR/raw"
cleanup() { docker rm -f "$C" >/dev/null 2>&1 || true; }
trap cleanup EXIT

psqlc() { docker exec "$C" psql -U root -d dockindb -X -q -v ON_ERROR_STOP=1 "$@"; }
psqli() { docker exec -i "$C" psql -U root -d dockindb -X -q -v ON_ERROR_STOP=1 "$@"; }
scalar() { psqlc -t -A -c "$1"; }
say() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT_DIR/run.log"; }
ms_now() { date +%s%3N; }

{
  echo "# area denorm lab  $(date +%FT%T%z)"
  echo "image=$IMAGE mem=$MEM rows=$ROWS rare_every=$RARE_EVERY users=$USERS write_rows=$WRITE_ROWS passes=\"$PASSES\""
  echo "host: $(uname -srm)"; docker version --format 'docker {{.Server.Version}}'
  echo "source: $(git rev-parse --short HEAD 2>/dev/null || echo '?')$([[ -n $(git status --porcelain -- scripts/db/area-denorm-lab.sh 2>/dev/null) ]] && echo ' (dirty: area-denorm-lab.sh 미커밋 수정 있음)')"
} > "$OUT_DIR/env.md"

# ── 컨테이너 ────────────────────────────────────────────────────────────────
docker run -d --name "$C" --memory="$MEM" \
    -e POSTGRES_USER=root -e POSTGRES_PASSWORD=x -e POSTGRES_DB=dockindb "$IMAGE" \
    -c lock_timeout=5s -c log_lock_waits=on -c shared_preload_libraries=pg_stat_statements \
    -c synchronize_seqscans=off >/dev/null   # trgm-lab 과 같다: 켜 두면 LIMIT 20 조기 종료가 12ms↔720ms 로 흔들린다
for _ in $(seq 1 90); do docker logs "$C" 2>&1 | grep -q "PostgreSQL init process complete" && break; sleep 1; done
for _ in $(seq 1 60); do docker exec "$C" psql -U root -d dockindb -X -q -t -A -c "SELECT 1" 2>/dev/null | grep -q '^1$' && break; sleep 1; done
say "컨테이너 기동. 시드 ${ROWS}행"
psqlc -c "SELECT name, setting, unit FROM pg_settings WHERE name IN ('shared_buffers','work_mem','maintenance_work_mem','effective_cache_size','max_parallel_workers_per_gather','random_page_cost')" > "$OUT_DIR/raw/settings.txt"

# ── 시드 (trgm-lab.sh 의 어휘·행 길이 그대로. users 표가 추가됐다) ─────────────
# users: 500명. 구역은 고르지 않다 — A0 는 i%6==0 인 84명(trgm-lab 의 구역 0 과 같은 사용자), 나머지 416명을
# 순서대로 A1 250 · A2 100 · A3 50 · A4 11 · A5 5 로 자른다. 운영 users 에는 ship_yard_area 인덱스가 없다(V4) — 여기서도 없다.
T=$(ms_now)
psqli <<EOF
CREATE EXTENSION IF NOT EXISTS pg_prewarm;
CREATE TABLE users (
    user_id varchar(50) PRIMARY KEY,
    name varchar(10) NOT NULL, ship_yard_area varchar(100) NOT NULL,
    role varchar(255) NOT NULL DEFAULT 'USER', created_at timestamp(6) NOT NULL DEFAULT now());
INSERT INTO users (user_id, name, ship_yard_area)
SELECT 'bch' || lpad(i::text, 4, '0'), 'u' || i,
       CASE WHEN i % 6 = 0 THEN 'A0'
            WHEN rk <= 250 THEN 'A1' WHEN rk <= 350 THEN 'A2' WHEN rk <= 400 THEN 'A3'
            WHEN rk <= 411 THEN 'A4' ELSE 'A5' END
  FROM (SELECT i, row_number() OVER (ORDER BY i) AS rk FROM generate_series(0, $USERS - 1) AS i WHERE i % 6 <> 0
        UNION ALL SELECT i, 0 FROM generate_series(0, $USERS - 1) AS i WHERE i % 6 = 0) t;
CREATE TABLE work_logs (
    log_id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    title varchar(256) NOT NULL, log_text text NOT NULL,
    created_at timestamp(6) NOT NULL, updated_at timestamp(6),
    user_id varchar(50) NOT NULL REFERENCES users(user_id), equipment_id bigint,
    status varchar(20) NOT NULL DEFAULT 'PENDING');
CREATE TABLE lex_equipment AS SELECT * FROM unnest(ARRAY['CO2 용접기','겐트리 크레인','무인 도장설비','플라즈마 절단기','고소작업대','유압 프레스','블라스팅 장비','이송 컨베이어','공기압축기','집진 설비','지게차','발전기']) WITH ORDINALITY AS t(w, n);
CREATE TABLE lex_part AS SELECT * FROM unnest(ARRAY['송급 롤러','유압 실린더','제어 패널','베어링부','감속기','배관 플랜지','전동기 축','냉각 팬','안전 스위치','케이블 그랜드']) WITH ORDINALITY AS t(w, n);
CREATE TABLE lex_symptom AS SELECT * FROM unnest(ARRAY['이상 진동이 발생했다','간헐적으로 정지했다','온도가 규정치를 넘었다','누유가 확인되었다','소음이 평소보다 커졌다','출력이 불안정했다','경보가 반복 발생했다','동작이 지연되었다','압력이 유지되지 않았다','표시값이 튀는 현상이 있었다','기동에 실패했다','과전류로 차단되었다']) WITH ORDINALITY AS t(w, n);
CREATE TABLE lex_cause AS SELECT * FROM unnest(ARRAY['체결 볼트 이완이','윤활 부족이','필터 막힘이','센서 접점 불량이','절연 저하가','개스킷 경화가','베어링 마모가','냉각수 부족이','제어 파라미터 오설정이','이물질 유입이']) WITH ORDINALITY AS t(w, n);
CREATE TABLE lex_action AS SELECT * FROM unnest(ARRAY['규정 토크로 재체결했다','그리스를 보충했다','필터를 교체했다','접점을 청소하고 재결선했다','절연 저항을 측정하고 케이블을 교체했다','개스킷을 신품으로 교체했다','베어링을 교체하고 정렬을 재조정했다','냉각수를 보충하고 누설 여부를 확인했다','파라미터를 기준값으로 되돌렸다','내부를 분해 청소했다','임시 조치 후 정비팀에 이관했다','예비품으로 교체했다']) WITH ORDINALITY AS t(w, n);
CREATE TABLE lex_follow AS SELECT * FROM unnest(ARRAY['다음 정기 점검에서 재확인이 필요하다.','동일 증상이 재발하면 부품 수명 도래로 보고 교체를 건의한다.','점검 주기를 단축하자고 반장에게 보고했다.','예비품 재고가 없어 발주를 요청했다.','인접 호기에서도 같은 증상이 있는지 확인하기로 했다.','작업 표준서에 이 절차가 빠져 있어 추가를 요청했다.','교대조에 경과 관찰을 인계했다.','원인이 완전히 규명되지 않아 기록만 남긴다.','재발 방지를 위해 체결 이력을 기록하기로 했다.','설비 이력 카드에 반영했다.']) WITH ORDINALITY AS t(w, n);
EOF
gen_rows() {   # $1=제목 접두사  $2=generate_series 인자  $3=i 오프셋(예: "+ 1000000")  $4=희귀 문장 CASE(없으면 빈 문자열)  — trgm-lab.sh 와 같다
    cat <<SQL
SELECT '$1' || e.w || ' ' || (1 + i % 7) || '호기 ' || p.w AS title,
       e.w || ' ' || (1 + i % 7) || '호기 ' || p.w || '에서 ' || s.w || '. '
         || '점검 결과 ' || c.w || ' 원인으로 확인되었다. ' || a.w || '. '
         || '조치 후 ' || (10 + (i * 7) % 50) || '분간 시운전하여 정상 동작을 확인했다. '
         || '측정값은 ' || ((i * 13) % 90) || '.' || (i % 10) || '이며 기준 범위 이내였다. '
         || f.w $4 AS log_text,
       TIMESTAMP '2024-08-01 06:00:00' + (i * INTERVAL '1 minute') AS created_at,
       TIMESTAMP '2024-08-01 06:00:00' + (i * INTERVAL '1 minute') AS updated_at,
       'bch' || lpad((i % $USERS)::text, 4, '0') AS user_id,
       NULL::bigint AS equipment_id
  FROM (SELECT g $3 AS i FROM generate_series($2) AS g) gs
  JOIN lex_equipment e ON e.n = 1 + (i * 7) % 12
  JOIN lex_part p      ON p.n = 1 + (i * 3) % 10
  JOIN lex_symptom s   ON s.n = 1 + (i * 11) % 12
  JOIN lex_cause c     ON c.n = 1 + (i * 7) % 10
  JOIN lex_action a    ON a.n = 1 + (i * 17) % 12
  JOIN lex_follow f    ON f.n = 1 + (i * 19) % 10
SQL
}
psqli <<EOF
INSERT INTO work_logs (title, log_text, created_at, updated_at, user_id, equipment_id)
$(gen_rows '[벤치] ' "1, $ROWS" "" "|| CASE WHEN i % $RARE_EVERY = 0 THEN ' 특이사항으로 크랭크축 균열을 발견했다.' ELSE '' END");
CREATE INDEX idx_work_logs_user ON work_logs (user_id);   -- V4
EOF
SEED_MS=$(( $(ms_now) - T ))
T=$(ms_now); psqlc -c "VACUUM work_logs"; VAC_MS=$(( $(ms_now) - T ))
T=$(ms_now); psqlc -c "ANALYZE work_logs; ANALYZE users"; ANALYZE_MS=$(( $(ms_now) - T ))
HEAP=$(scalar "SELECT pg_size_pretty(pg_relation_size('work_logs'))")
say "시드 $((SEED_MS/1000))s, VACUUM $((VAC_MS/1000))s, ANALYZE $((ANALYZE_MS/1000))s, 힙 $HEAP, 희귀 행 $(scalar "SELECT count(*) FROM work_logs WHERE log_text LIKE '%크랭크축%'")건"
echo "seed_ms=$SEED_MS vacuum_ms=$VAC_MS analyze_ms=$ANALYZE_MS heap=$HEAP" >> "$OUT_DIR/env.md"
psqlc -c "SELECT ship_yard_area, count(*) AS users, (SELECT count(*) FROM work_logs w JOIN users x USING (user_id) WHERE x.ship_yard_area = u.ship_yard_area) AS logs FROM users u GROUP BY 1 ORDER BY 1" | tee -a "$OUT_DIR/env.md"

# 중간 커서: 전체를 최신순으로 세워 정확히 가운데 행. 세 구역 모두 같은 커서를 쓴다(#127 이 잰 "중간 페이지" 조건).
IFS='|' read -r CUR_TS CUR_ID < <(scalar "SELECT created_at || '|' || log_id FROM work_logs ORDER BY created_at DESC, log_id DESC OFFSET $((ROWS/2)) LIMIT 1")
echo "cursor: created_at=$CUR_TS log_id=$CUR_ID" >> "$OUT_DIR/env.md"

# ── 쿼리 12개 ────────────────────────────────────────────────────────────────
# Hibernate 가 내는 문장 꼴을 따른다. 첫 페이지는 커서 파라미터가 NULL — 플래너가 IS NULL 을 접는다.
# 목록은 LIMIT 21(Slice 가 hasNext 를 보려고 +1), 검색은 LIMIT 20(searchWorkLogs 의 Pageable 과 같다… 실제로는 둘 다 Slice 라 21).
AREAS_Q="A0 A1 A5"
in_list() { psqlc -t -A -c "SELECT string_agg(quote_literal(user_id), ',' ORDER BY user_id) FROM users WHERE ship_yard_area = '$1'"; }
where_area() {   # $1=state $2=area  → 구역 조건 (FROM 절 포함)
    case $1 in
        O) echo "FROM work_logs w WHERE w.user_id IN ($(in_list "$2"))" ;;
        A) echo "FROM work_logs w JOIN users u ON u.user_id = w.user_id WHERE u.ship_yard_area = '$2'" ;;
        B) echo "FROM work_logs w WHERE w.ship_yard_area = '$2'" ;;
    esac
}
CUR_NULL="(CAST(NULL AS timestamp) IS NULL OR (w.created_at <= NULL AND (w.created_at < NULL OR w.log_id < NULL)))"
CUR_MID="(w.created_at <= '$CUR_TS' AND (w.created_at < '$CUR_TS' OR w.log_id < $CUR_ID))"
STATUS_NULL="(CAST(NULL AS varchar) IS NULL OR w.status = NULL)"
ORDER="ORDER BY w.created_at DESC, w.log_id DESC LIMIT 21"
build_queries() {   # $1=state → 연관배열 Q 를 채운다
    local st=$1 a
    Q=()
    for a in $AREAS_Q; do
        Q[L1_$a]="SELECT w.* $(where_area "$st" "$a") AND $STATUS_NULL AND $CUR_NULL $ORDER"
        Q[L2_$a]="SELECT w.* $(where_area "$st" "$a") AND $STATUS_NULL AND $CUR_MID $ORDER"
        Q[S1_$a]="SELECT w.* $(where_area "$st" "$a") AND (w.title LIKE '%크랭크축%' OR w.log_text LIKE '%크랭크축%') AND $CUR_NULL $ORDER"
        Q[S2_$a]="SELECT w.* $(where_area "$st" "$a") AND (w.title LIKE '%베어링%' OR w.log_text LIKE '%베어링%') AND $CUR_NULL $ORDER"
    done
}
QORDER=""; for a in $AREAS_Q; do QORDER="$QORDER L1_$a L2_$a S1_$a S2_$a"; done
declare -A Q

echo "pass,state,query,run,ms" > "$OUT_DIR/timings.csv"
echo "pass,state,step,ms,size" > "$OUT_DIR/migration.csv"
echo "pass,state,round,write_rows,insert_ms" > "$OUT_DIR/writes.csv"

prewarm() {
    scalar "SELECT coalesce(sum(pg_prewarm(c.oid, 'read')), 0) FROM pg_class c WHERE c.oid IN ('work_logs'::regclass, 'users'::regclass) OR c.oid IN (SELECT indexrelid FROM pg_index WHERE indrelid IN ('work_logs'::regclass, 'users'::regclass))"
}

measure_pass() {   # $1=pass번호 $2=state
    local pass=$1 state=$2 f="$OUT_DIR/raw/pass${pass}-${state}-timing.txt"
    build_queries "$state"
    { for q in $QORDER; do echo "-- $state $q"; echo "${Q[$q]}"; echo; done; } > "$OUT_DIR/raw/pass${pass}-${state}-queries.sql"
    {
      echo '\o /dev/null'
      echo '\timing on'
      echo '\pset pager off'
      for q in $QORDER; do
        for r in 1 2 3 4 5 6 7; do echo "\\echo == $q run $r"; echo "${Q[$q]};"; done
      done
    } | psqli > "$f" 2>&1
    awk -v pass="$pass" -v state="$state" '
        /^== /   { q=$2; r=$4 }
        /^Time: / { sub(/ ms/, "", $2); print pass "," state "," q "," r "," $2 }' "$f" >> "$OUT_DIR/timings.csv"
    for q in $QORDER; do
        { echo "-- $q"; psqlc -c "EXPLAIN (ANALYZE, BUFFERS) ${Q[$q]}"; echo; } >> "$OUT_DIR/raw/pass${pass}-${state}-plans.txt"
    done
}

# 쓰기 비용. B 에서는 ship_yard_area 를 같이 넣어야 한다(NOT NULL) — 서비스가 작성자 구역을 복사해 넣는 꼴을 흉내 낸다.
write_cost() {   # $1=pass $2=state
    local pass=$1 state=$2 T ms out="" cols sel
    for round in 1 2 3; do
        cols="title, log_text, created_at, updated_at, user_id, equipment_id"
        sel=$(gen_rows '[쓰기] ' "1, $WRITE_ROWS" "+ $ROWS" "")
        if [[ $state == B ]]; then
            cols="$cols, ship_yard_area"
            sel="SELECT g.*, u.ship_yard_area FROM ($sel) g JOIN users u ON u.user_id = g.user_id"
        fi
        T=$(ms_now)
        psqli >/dev/null <<EOF || { say "!! 패스 $pass [$state] INSERT 실패"; exit 1; }
INSERT INTO work_logs ($cols)
$sel;
EOF
        ms=$(( $(ms_now) - T ))
        echo "$pass,$state,$round,$WRITE_ROWS,$ms" >> "$OUT_DIR/writes.csv"
        psqlc -c "DELETE FROM work_logs WHERE title LIKE '[쓰기] %'" >/dev/null
        psqlc -c "VACUUM work_logs" >/dev/null
        out="$out $ms"
    done
    echo "$out"
}

timed() {   # $1=pass $2=state $3=step $4=sql [$5=size-of]  → migration.csv
    local T ms size=""
    T=$(ms_now); psqlc -c "$4" >/dev/null; ms=$(( $(ms_now) - T ))
    [[ -n ${5:-} ]] && size=$(scalar "SELECT pg_size_pretty(pg_relation_size('$5'))")
    echo "$1,$2,$3,$ms,$size" >> "$OUT_DIR/migration.csv"
    say "  $3: $((ms/1000)).$(printf %03d $((ms%1000)))s ${size:+크기 $size}"
}

# ── 패스 ────────────────────────────────────────────────────────────────────
# 상태 전이는 한 방향으로만 쌓는다: O(V4만) → A(V10 추가) → B(컬럼+인덱스 추가) → A(컬럼·인덱스 제거) → B(다시 추가).
# 두 번째 B 의 컬럼 추가는 첫 번째와 같은 비용이라 이관 비용도 두 번 재는 셈이다.
pass=0
for state in $PASSES; do
    pass=$((pass+1))
    case $state in
        O) psqlc -c "DROP INDEX IF EXISTS idx_work_logs_created; DROP INDEX IF EXISTS idx_work_logs_area_created; ALTER TABLE work_logs DROP COLUMN IF EXISTS ship_yard_area" >/dev/null ;;
        A) psqlc -c "DROP INDEX IF EXISTS idx_work_logs_area_created; ALTER TABLE work_logs DROP COLUMN IF EXISTS ship_yard_area" >/dev/null
           if [[ $(scalar "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_work_logs_created'") == 0 ]]; then
               say "패스 $pass [A] V10 인덱스 생성"
               timed "$pass" A "V10 create index (created_at DESC · log_id DESC)" "CREATE INDEX idx_work_logs_created ON work_logs (created_at DESC, log_id DESC)" idx_work_logs_created
           fi
           # 컬럼을 지웠다 다시 만들면 힙에 죽은 판이 남는다 — 다음 B 의 백필이 앞선 것과 같은 조건이 되도록 여기서 VACUUM FULL 대신 일반 VACUUM 만(운영과 같다)
           psqlc -c "VACUUM work_logs" >/dev/null ;;
        B) say "패스 $pass [B] 비정규화 컬럼 + 백필 + 인덱스"
           timed "$pass" B "add column ship_yard_area" "ALTER TABLE work_logs ADD COLUMN ship_yard_area varchar(100)"
           timed "$pass" B "backfill UPDATE from users" "UPDATE work_logs w SET ship_yard_area = u.ship_yard_area FROM users u WHERE u.user_id = w.user_id"
           timed "$pass" B "set not null" "ALTER TABLE work_logs ALTER COLUMN ship_yard_area SET NOT NULL"
           # 백필 UPDATE 는 100만 행 전부를 새 버전으로 쓴다 — 힙이 두 배가 되고 일반 VACUUM 은 그걸 못 돌려준다(연기 시험 24→49MB).
           # 그대로 두면 B(그리고 그 뒤의 A)가 A 의 첫 패스보다 힙 페이지를 두 배 읽어 비교가 기운다. VACUUM FULL 로 되돌리고
           # 그 시간도 이관 비용으로 적는다(운영이면 pg_repack 자리 — VACUUM FULL 은 표를 통째로 잠근다).
           say "  힙(백필 직후) $(scalar "SELECT pg_size_pretty(pg_relation_size('work_logs'))")"
           timed "$pass" B "vacuum full after backfill" "VACUUM FULL work_logs" work_logs
           timed "$pass" B "create index (ship_yard_area · created_at DESC · log_id DESC)" "CREATE INDEX idx_work_logs_area_created ON work_logs (ship_yard_area, created_at DESC, log_id DESC)" idx_work_logs_area_created ;;
    esac
    # 패스마다 전체 ANALYZE 를 하면 title·log_text 표본 정렬(en_US.utf8 strcoll)에 2분씩 든다 — 인덱스·컬럼이 바뀌어도 본문 통계는 안 변한다.
    # 필터·정렬 키 컬럼만 다시 센다(연기 시험: 전체 93~127s → 컬럼 지정 1s 미만).
    cols="user_id, created_at, log_id, status"; [[ $state == B ]] && cols="$cols, ship_yard_area"
    T=$(ms_now); psqlc -c "ANALYZE work_logs ($cols)" >/dev/null; ANALYZE_MS=$(( $(ms_now) - T ))
    warmed=$(prewarm)
    say "패스 $pass [$state] ANALYZE $((ANALYZE_MS/1000))s, prewarm ${warmed}블록, 힙 $(scalar "SELECT pg_size_pretty(pg_relation_size('work_logs'))"), 쿼리 12개 × 7회"
    measure_pass "$pass" "$state"
    say "패스 $pass [$state] 쓰기 ${WRITE_ROWS}행 × 3:$(write_cost "$pass" "$state") ms"
done
psqlc -c "SELECT indexrelid::regclass AS index, pg_size_pretty(pg_relation_size(indexrelid)) FROM pg_index WHERE indrelid = 'work_logs'::regclass" > "$OUT_DIR/raw/sizes-final.txt"
psqlc -c "SELECT pg_size_pretty(pg_relation_size('work_logs')) heap, pg_size_pretty(pg_indexes_size('work_logs')) indexes, pg_size_pretty(pg_total_relation_size('work_logs')) total" >> "$OUT_DIR/raw/sizes-final.txt"
docker logs "$C" > "$OUT_DIR/raw/postgres.log" 2>&1 || true

# ── 요약 ────────────────────────────────────────────────────────────────────
PYTHON=$(command -v python || command -v python3)
"$PYTHON" - "$OUT_DIR" "$HEAP" <<'PY'
import csv, sys, statistics as st
from collections import defaultdict
out, heap = sys.argv[1:]
t = defaultdict(list)
for r in csv.DictReader(open(f"{out}/timings.csv")):
    if int(r["run"]) > 2:
        t[(int(r["pass"]), r["state"], r["query"])].append(float(r["ms"]))
passes = sorted({k[0] for k in t})
state_of = {p: next(k[1] for k in t if k[0]==p) for p in passes}
areas = ["A0", "A1", "A5"]; kinds = ["L1", "L2", "S1", "S2"]
queries = [f"{k}_{a}" for a in areas for k in kinds]
bad = {(p, q): len(t.get((p, state_of[p], q), [])) for p in passes for q in queries if len(t.get((p, state_of[p], q), [])) != 5}
assert not bad, f"timings.csv 표본이 5개가 아닌 자리: {bad}"
kname = {"L1":"목록 첫 페이지","L2":"목록 중간 커서","S1":"검색 희귀 '크랭크축'","S2":"검색 흔함 '베어링'"}
aname = {"A0":"A0 84명 17%","A1":"A1 250명 50%","A5":"A5 5명 1%"}
def med(p, q): return st.median(t[(p, state_of[p], q)])
def mean_state(s, q):
    v = [med(p, q) for p in passes if state_of[p] == s]; return st.mean(v) if v else None
hdr = "| 구역 | 쿼리 | " + " | ".join(f"패스{p} {state_of[p]}" for p in passes) + " | A→B |"
lines = [f"힙 {heap}", "", hdr, "|---|---|" + "---|"*len(passes) + "---|"]
for a in areas:
    for k in kinds:
        q = f"{k}_{a}"
        ma, mb = mean_state("A", q), mean_state("B", q)
        ratio = f"{ma/mb:.1f}배" if ma and mb and mb > 0 else "-"
        lines.append(f"| {aname[a]} | {kname[k]} | " + " | ".join(f"{med(p, q):.1f}" for p in passes) + f" | {ratio} |")
lines += ["", "(ms, 5회 중앙값. 1·2회 워밍업 제외. O = #127 이전 IN 목록, A = #127 조인+V10, B = 비정규화 컬럼+인덱스)", "",
          "| 패스 | 상태 | 이관 단계 | 시간 | 크기 |", "|---|---|---|---|---|"]
for r in csv.DictReader(open(f"{out}/migration.csv")):
    lines.append(f"| {r['pass']} | {r['state']} | {r['step']} | {int(r['ms'])/1000:.1f}s | {r['size']} |")
w = defaultdict(list); wrows = 0
for r in csv.DictReader(open(f"{out}/writes.csv")):
    w[(int(r["pass"]), r["state"])].append(int(r["insert_ms"])/1000); wrows = int(r["write_rows"])
lines += ["", f"| 패스 | 상태 | INSERT {wrows:,}행 × 3 | 중앙값 |", "|---|---|---|---|"]
for (p, s), v in sorted(w.items()):
    lines.append(f"| {p} | {s} | {' / '.join(f'{x:.1f}' for x in v)} | {st.median(v):.1f}s |")
wa = [st.median(v) for (p, s), v in w.items() if s == 'A']; wb = [st.median(v) for (p, s), v in w.items() if s == 'B']
if wa and wb: lines.append(f"\n쓰기 A→B: {st.mean(wb)/st.mean(wa):.2f}배 (중앙값들의 평균끼리; B 는 users 조인으로 구역을 채우는 비용 포함)")
open(f"{out}/summary.md","w",encoding="utf-8").write("\n".join(lines)+"\n"); print("\n".join(lines))
PY
say "산출물: $OUT_DIR"
