package com.DOCKin.global.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

/**
 * 비동기 작업에 <b>제출한 시점의</b> SecurityContext와 MDC({@code traceId})를 실어 보낸다.
 *
 * <h3>왜 {@code MODE_INHERITABLETHREADLOCAL}이 아닌가 (백로그 P2-18-11)</h3>
 * 그 전략은 스레드가 <b>만들어질 때</b> 부모의 컨텍스트를 복사한다. 풀 스레드는 첫 작업을
 * 받을 때 만들어지고 그 뒤로 재사용되므로, 첫 사용자의 컨텍스트를 <b>영원히</b> 든다.
 * 나중에 다른 사용자의 작업을 그 스레드가 집으면 앞 사용자로 실행된다. 지금은 {@code @Async}
 * 코드가 {@code SecurityContextHolder}를 읽지 않아 무사했지만, ADR-0008의 커밋 뒤 리스너가
 * "현재 사용자"를 읽는 순간 터지는 자리였다.
 *
 * <p>여기서는 작업을 <b>제출할 때</b> 잡아서 <b>실행 직전</b>에 넣고 <b>끝나면 지운다.</b>
 * 스레드가 아니라 작업 단위라 재사용이 문제되지 않는다. Spring Security의
 * {@code DelegatingSecurityContextRunnable}이 하는 일과 같고, MDC까지 함께 옮기려고 직접 둔다 —
 * {@code traceId}가 비동기 경계에서 끊기면 P2-11-3에서 MDC를 이은 이유가 반쪽이 된다.
 */
public class ContextPropagatingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        SecurityContext security = SecurityContextHolder.getContext();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> {
            try {
                SecurityContextHolder.setContext(security);
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                task.run();
            } finally {
                // 풀 스레드에 남기지 않는다. 다음 작업이 이 사용자로 실행되면 안 된다.
                SecurityContextHolder.clearContext();
                MDC.clear();
            }
        };
    }
}
