package com.DOCKin.global.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 커넥션 풀 고갈 감시 — 커넥션을 기다리는 스레드가 있으면 WARN 한 줄 (DB-IMPROVEMENT-PLAN B4, PRODUCTION-READINESS O2).
 *
 * <h3>왜 로그인가</h3>
 * 알림 인프라(Prometheus·Slack)가 아직 없다. {@code /actuator/metrics/hikaricp.connections.pending}은
 * 있지만 <b>아무도 호출받지 않는다</b>(O2 ❌). 그 자리를 로그 한 줄이 메운다 — 로그 집계(O3)가 붙으면
 * 이 줄이 곧 알림 조건이고, 그 전에도 {@code grep "HikariPool 대기"}로 사후에 찾을 수 있다.
 *
 * <h3>왜 pending인가 — M1의 교훈</h3>
 * ADR-0008 7-1: 채팅 수신 지연 p50 726~2,764ms의 원인은 전파가 아니라 <b>풀 고갈</b>이었다.
 * {@code @Async}가 실행기 이름 없이 스레드 631개를 만들어 각자 커넥션을 잡았고, 인바운드 스레드가
 * 커넥션을 기다린 시간이 곧 지연이 됐다. 그때 pending을 보고 있었다면 "풀을 늘려라"가 아니라
 * "누가 631개를 잡고 있나"로 바로 갔을 것이다. 그래서 이 줄은 pending과 함께 <b>active/idle/total</b>을
 * 같이 찍는다 — total이 max에 붙어 있고 active가 그만큼이면 고갈이고, 아니면 다른 문제다.
 *
 * <h3>풀을 늘리는 것이 답이 아닌 이유</h3>
 * PostgreSQL은 커넥션 하나가 백엔드 프로세스 하나다(『막힘없이 PostgreSQL』 1장). 풀을 올린 만큼
 * DB 메모리를 낸다. 그래서 {@code maximum-pool-size}는 기본값 10을 <b>명시만</b> 하고 올리지 않았다 —
 * 값을 바꾸려면 ADR-0004 3-2의 부하 실측이 먼저다.
 *
 * <p>주기는 {@code hikari.watch.interval-ms}(기본 10초). 풀 상태를 읽는 것뿐이라 비용은 없다.
 * pending이 0이면 아무것도 찍지 않는다 — 조용한 것이 정상이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hikari.watch.enabled", havingValue = "true", matchIfMissing = true)
public class HikariPoolWatch {

    private final HikariDataSource dataSource;

    public HikariPoolWatch(DataSource dataSource) {
        // Spring Boot 기본 DataSource는 HikariDataSource다. 아니면(테스트에서 다른 것을 꽂았다면) 감시할 풀이 없다.
        this.dataSource = dataSource instanceof HikariDataSource h ? h : null;
    }

    @Scheduled(fixedDelayString = "${hikari.watch.interval-ms:10000}")
    public void check() {
        if (dataSource == null) return;
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        if (pool == null) return;   // 아직 풀이 안 만들어졌다(기동 직후)

        int pending = pool.getThreadsAwaitingConnection();
        if (pending == 0) return;

        log.warn("HikariPool 대기 pending={} active={} idle={} total={} max={} — 풀 고갈이면 '누가 잡고 있나'부터 (ADR-0008 7-1)",
                pending, pool.getActiveConnections(), pool.getIdleConnections(),
                pool.getTotalConnections(), dataSource.getMaximumPoolSize());
    }
}
