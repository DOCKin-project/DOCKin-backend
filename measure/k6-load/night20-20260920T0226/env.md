# 밤 20 — traffic.js 1,000 req/s × 10분 (2026-09-20 02:26~03:07 UTC)

- 서버 m7i.2xlarge(8 vCPU) ap-northeast-2a, 부하기 c7i.xlarge, 같은 서브넷 사설 IP로 nginx:80
- 서버 코드: r1000 = `measure/random-traffic`(main 57c5c29 + traffic.js), r1000-fix·r1000-cpu4 = `fix/jwt-parser-reuse` a0b2250 (#162)
- compose.yaml + compose.gc.yaml + compose.k6mem.yaml (기동 색인 끔, 앱 1G). 앱 cpus 1.0 → r1000-cpu4 만 `docker update --cpus=4`
- 계정 k6u00001..40000 + 작업일지 12만 (seed-users.sql), 단계 사이 reset-attendance.sql
- k6 v2.2.0 (부하기 dnf), traffic.js 기본값: RATE=1000 PATTERN=random JITTER=0.5 STEP=15 SESSIONS=300 LOGIN_PCT=0.5 MAX_VUS=2000
- sanity*.json 은 스크립트 고치는 동안의 100/s 짧은 실행(sanity1~3 은 VU 생성 로그인 폭풍 버전, sanity4 는 구역 계산 버그)
