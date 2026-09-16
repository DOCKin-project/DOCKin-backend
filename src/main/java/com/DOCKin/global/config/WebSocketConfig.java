package com.DOCKin.global.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final StompHandler stompHandler;
    private final StompExceptionHandler stompExceptionHandler;

    /**
     * 허용 오리진. {@link WebConfig}와 같은 설정값을 공유한다.
     *
     * <p>기존에는 {@code setAllowedOriginPatterns("*")}에 {@code //추후 포트 알면 변경}
     * 주석이 달려 있었다. HTTP 쪽(WebConfig)만 조이고 WebSocket을 열어두면
     * <b>같은 인증 정보로 들어오는 경로 하나가 그대로 열려 있는 것</b>이라 의미가 없다.
     */
    @Value("${security.cors.allowed-origins}")
    private String[] allowedOrigins;

    //엔드포인트 등록을 위한 설정
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry){
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins);

        registry.setErrorHandler(stompExceptionHandler);
    }

    /**
     * heartbeat용 스케줄러. Spring이 브로커 설정과 함께 만드는 {@code messageBrokerTaskScheduler}를 그대로 쓴다.
     * {@code @Lazy}는 이 설정 클래스가 그 빈보다 먼저 만들어지는 순환을 끊기 위한 것(Spring 레퍼런스의 방식).
     */
    @Autowired @Lazy
    private TaskScheduler messageBrokerTaskScheduler;

    /**
     * prefix로 sub이 붙으면 구독, pub이 붙으면 메시지 송신.
     *
     * <h3>heartbeat 10초 (ADR-0008 D8의 클라이언트 몫, P2-12-5)</h3>
     * 이전에는 heartbeat가 없었다 — {@code enableSimpleBroker}만 부르면 {@code 0,0}으로 협상돼 어느 쪽도
     * 심장박동을 보내지 않는다. 그러면 Wi-Fi가 조용히 죽었을 때(현장에서 흔하다) TCP는 살아 있는 것으로
     * 보이고, 클라이언트는 몇 분이 지나도 {@code onclose}를 받지 못한다. <b>끊긴 줄을 모르면 따라잡기가
     * 시작될 일이 없다</b> — {@code GET .../messages/after?seq=}가 있어도 부를 계기가 없다.
     * 10초는 stomp.js의 기본값과 같고, 서버는 10초마다 한 줄(LF 한 바이트)을 보내며 클라이언트가 3주기 동안
     * 조용하면 세션을 닫는다. 서버 쪽 세션 정리도 같은 값에 걸린다 — 좀비 세션이 브로커 구독 목록에
     * 남지 않는다.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry){
        registry.enableSimpleBroker("/sub")
                .setHeartbeatValue(new long[]{10_000, 10_000})
                .setTaskScheduler(messageBrokerTaskScheduler);
        registry.setApplicationDestinationPrefixes("/pub");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration){
        registration.interceptors(stompHandler);
    }
}
