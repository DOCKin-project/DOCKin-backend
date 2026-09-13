package com.DOCKin.global.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    //prefix로 sub이 붙으면 구독, pub이 붙으면 메시지 송신
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry){
        registry.enableSimpleBroker("/sub");
        registry.setApplicationDestinationPrefixes("/pub");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration){
        registration.interceptors(stompHandler);
    }
}
