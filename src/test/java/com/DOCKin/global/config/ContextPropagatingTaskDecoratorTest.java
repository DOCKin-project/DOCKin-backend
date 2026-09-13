package com.DOCKin.global.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 검증: 풀 스레드가 <b>작업을 제출한 사용자</b>로 실행되고, 끝나면 <b>아무도 아니다</b> (백로그 P2-18-11).
 *
 * <p>{@code MODE_INHERITABLETHREADLOCAL}이었을 때는 스레드가 만들어질 때의 사용자를 영원히 들었다.
 * 스레드 하나짜리 풀로 그 차이를 그대로 본다 — 첫 작업이 u1로, 두 번째 작업은 u2로, 세 번째는
 * 아무도 아닌 채로 <b>같은 스레드</b>에서 돈다.
 */
class ContextPropagatingTaskDecoratorTest {

    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.initialize();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    @DisplayName("같은 풀 스레드가 u1 → u2 → 익명 순으로, 제출한 쪽의 사용자로 돈다")
    void 작업_단위_전파() throws Exception {
        String t1 = runAs("u1");
        String t2 = runAs("u2");
        String t3 = runAs(null);

        assertEquals("u1", t1);
        assertEquals("u2", t2);
        assertNull(t3, "앞 작업의 사용자가 스레드에 남아 있다");
    }

    @Test
    @DisplayName("traceId도 함께 간다 - 비동기 경계에서 로그가 끊기지 않는다")
    void MDC_전파() throws Exception {
        MDC.put("traceId", "abc123");
        Future<String> seen = executor.submit(() -> MDC.get("traceId"));
        MDC.clear();
        Future<String> after = executor.submit(() -> MDC.get("traceId"));

        assertEquals("abc123", seen.get());
        assertNull(after.get(), "앞 작업의 traceId가 스레드에 남아 있다");
    }

    /** {@code userId}로 인증된 채(또는 null이면 익명으로) 작업을 내고, 작업이 본 사용자 이름을 돌려준다. */
    private String runAs(String userId) throws InterruptedException, ExecutionException {
        if (userId == null) {
            SecurityContextHolder.clearContext();
        } else {
            Authentication auth = new TestingAuthenticationToken(userId, null, "ROLE_USER");
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        Future<String> seen = executor.submit(() -> {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            return a == null ? null : a.getName();
        });
        String result = seen.get();
        SecurityContextHolder.clearContext();
        return result;
    }
}
