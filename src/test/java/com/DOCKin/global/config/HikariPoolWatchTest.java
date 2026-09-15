package com.DOCKin.global.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 풀이 고갈되면 {@link HikariPoolWatch}가 WARN을 찍는다 — 그리고 고갈되지 않으면 아무것도 안 찍는다.
 *
 * <p>풀을 2로 줄이고 둘 다 잡은 채 세 번째 요청을 보낸다. 그 스레드는 {@code connection-timeout}까지
 * 기다리고, 그동안 {@code pending=1}이다. 감시 주기를 200ms로 두어 그 창 안에 감시기가 한 번은 돈다.
 *
 * <p>"찍힌다"만 보면 <b>항상 찍는 감시기</b>도 통과한다. 그래서 풀이 비기 전(pending=0) 구간의
 * WARN이 0건인 것도 같이 본다 — 조용한 것이 정상이라는 계약이다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=3000",
        "hikari.watch.interval-ms=200"
})
class HikariPoolWatchTest extends ContainerTestSupport {

    @Autowired
    private DataSource dataSource;

    private ListAppender<ILoggingEvent> appender;
    private Logger watchLogger;

    @BeforeEach
    void attachAppender() {
        watchLogger = (Logger) LoggerFactory.getLogger(HikariPoolWatch.class);
        appender = new ListAppender<>();
        appender.start();
        watchLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        watchLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("커넥션을 기다리는 스레드가 생기면 WARN 한 줄, 없으면 0줄")
    void 풀_고갈이면_경고한다() throws Exception {
        // 1. 조용한 구간 — 풀에 여유가 있는 동안 감시기가 몇 번 돌아도 아무것도 안 찍어야 한다.
        Thread.sleep(600);
        assertEquals(0, warnCount(), "풀에 여유가 있는데 WARN이 찍혔다 - 감시기가 조건 없이 찍고 있다");

        // 2. 둘 다 잡는다.
        List<Connection> held = new ArrayList<>();
        for (int i = 0; i < 2; i++) held.add(dataSource.getConnection());

        // 3. 세 번째 — connection-timeout(3s)까지 기다린다. 그동안 pending=1.
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try (Connection ignored = dataSource.getConnection()) {
                // 타임아웃 전에 held를 놓아 주므로 결국 얻는다
            } catch (Exception ignored) {
                // 타임아웃이면 SQLTransientConnectionException - 이 테스트의 관심사가 아니다
            } finally {
                done.countDown();
            }
        }, "pool-waiter");
        waiter.start();

        try {
            Thread.sleep(1000);   // 감시 주기 200ms의 다섯 배. 최소 한 번은 pending=1을 본다
            long warns = warnCount();
            assertTrue(warns >= 1, "pending=1인 1초 동안 WARN이 한 줄도 없다");
            String msg = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN).findFirst().orElseThrow().getFormattedMessage();
            assertTrue(msg.contains("pending=1"), "pending 수가 틀리다: " + msg);
            assertTrue(msg.contains("active=2") && msg.contains("max=2"), "풀 상태(active/max)가 같이 안 찍혔다: " + msg);

            System.out.println();
            System.out.println("=== HikariPoolWatch ===");
            System.out.println("  조용한 구간 WARN : 0");
            System.out.println("  고갈 구간 WARN   : " + warns + "  (1초 / 200ms 주기)");
            System.out.println("  첫 줄            : " + msg);
            System.out.println();
        } finally {
            for (Connection c : held) c.close();
            assertTrue(done.await(5, TimeUnit.SECONDS), "대기 스레드가 커넥션을 돌려받지 못했다");
        }
    }

    private long warnCount() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
    }
}
