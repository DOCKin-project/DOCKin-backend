# E1 측정 환경 — 20260813T163244

| | |
|---|---|
| 인스턴스 | m7i.2xlarge |
| vCPU / 메모리 | 8 / 30GB |
| 커널 | 6.1.177-224.371.amzn2023.x86_64 |
| docker | Docker version 25.0.14 |
| compose 파일 | `-f compose.yaml -f compose.gc.yaml` |
| TEI 외 상한 합 | 3.00 cpus |
| shared_buffers | 128MB |
| work_mem | 4MB |
| maintenance_work_mem | 64MB |
| 창 길이 | 300s (워밍업 50청크 이후) |
| 조건 | 1.5 2.0 3.0 1.5 |
| 시작 시점 코퍼스 | 190860 청크 |
| CPU 모델 | Intel(R) Xeon(R) Platinum 8488C |
| **CPU 지문** | **706 ms** (단일 스레드 고정 루프 / GNU Awk 5.1.0, API: 3.0 (GNU MPFR 4.1.0-p13, GNU MP 6.2.1)) |
