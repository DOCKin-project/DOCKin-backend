# E1 측정 환경 — 20260812T171704

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
| 조건 | 2.0 3.0 4.0 5.0 6.0 2.0 |
| 시작 시점 코퍼스 | 25815 청크 |
