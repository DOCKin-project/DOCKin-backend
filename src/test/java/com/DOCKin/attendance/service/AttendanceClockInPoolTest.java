package com.DOCKin.attendance.service;

import com.DOCKin.attendance.dto.ClockInRequestDto;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회귀: 출근 한 건이 커넥션을 <b>하나만</b> 쓰는가 (#115, PR #126).
 *
 * <p>밤 15(k6)에서 출근 요청 하나가 커넥션 둘을 동시에 요구해, 동시 출근이 풀 크기만큼 모이면 각자 하나씩 쥔 채
 * 두 번째를 기다리는 <b>풀 데드락</b>이 났다. {@code NOT_SUPPORTED} 안에서 리포지토리 쿼리 메서드가 스코프 끝까지
 * 커넥션을 쥐기 때문이다(#93). PR #126이 조회·확인·저장을 {@code TransactionTemplate} 하나로 묶어 고쳤다.
 *
 * <p>{@code AttendanceServiceTest}는 트랜잭션 매니저가 목이라 커넥션 수를 볼 수 없다. 여기서는 <b>풀을 2로 줄이고</b>
 * 서로 다른 사용자 8명이 동시에 출근한다. 요청당 커넥션이 하나면 순서대로 빠지고, 둘이면 두 요청이 하나씩 쥔 채
 * 멈춰 {@code connection-timeout}(2초)에 실패한다. 수정 전 코드(리포지토리 호출 셋이 각자 트랜잭션)로 되돌려
 * 이 테스트가 실제로 실패하는 것을 확인했다.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=2",
        "spring.datasource.hikari.connection-timeout=2000"
})
@DisplayName("출근 풀 데드락 회귀 - 풀 2개에 동시 출근 8건이 전부 성공한다 (#115)")
class AttendanceClockInPoolTest extends ContainerTestSupport {

    private static final String PREFIX = "pool";
    private static final int USERS = 8;

    @Autowired private AttendanceService attendanceService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void users() {
        cleanup();
        for (int i = 0; i < USERS; i++) {
            jdbc.update("""
                    INSERT INTO users (user_id, name, password, role, language_code,
                                       ship_yard_area, remaining_leave_days, tts_enabled, work_shift, created_at)
                    VALUES (?, ?, ?, 'USER', 'ko', '풀구역', 15, false, 'MORNING', ?)
                    """, PREFIX + i, PREFIX + i, "$2a$10$" + "0".repeat(53), LocalDateTime.of(2026, 1, 1, 0, 0));
        }
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM attendance WHERE user_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM users WHERE user_id LIKE ?", PREFIX + "%");
    }

    @Test
    @DisplayName("서로 다른 8명이 동시에 출근해도 풀(2) 대기 시간 초과 없이 전부 기록된다")
    void concurrentClockInsShareSmallPool() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(USERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            String userId = PREFIX + i;
            futures.add(pool.submit(() -> {
                start.await();
                return attendanceService.clockin(userId, new ClockInRequestDto("제1조선소"));
            }));
        }
        start.countDown();

        List<String> failures = new ArrayList<>();
        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                failures.add(String.valueOf(e.getCause() != null ? e.getCause() : e));
            }
        }
        pool.shutdownNow();

        assertThat(failures).as("요청당 커넥션이 둘이면 여기서 'Connection is not available'로 실패한다").isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance WHERE user_id LIKE ?", Integer.class, PREFIX + "%"))
                .isEqualTo(USERS);
    }
}
