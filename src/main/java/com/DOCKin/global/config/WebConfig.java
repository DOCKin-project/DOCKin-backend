package com.DOCKin.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 설정.
 *
 * <h3>{@code allowedOriginPatterns("*")}를 걷어냈다 (2026-08-05)</h3>
 * 기존 설정은 <b>모든 오리진을 허용하면서 동시에 {@code allowCredentials(true)}</b>였다.
 * 이 조합이 위험한 이유는 <b>아무 사이트나 사용자의 인증 정보를 실어 이 API를 호출하고
 * 그 응답을 읽을 수 있다</b>는 뜻이기 때문이다.
 *
 * <p>스펙상 {@code allowedOrigins("*")}와 {@code allowCredentials(true)}는 함께 쓸 수 없어
 * 스프링이 예외로 막는다. 그런데 {@code allowedOriginPatterns}는 그 검사를 통과한다 —
 * <b>같은 위험을 예외 없이 지나가게 하는 우회로였던 셈이다.</b>
 *
 * <h3>허용 오리진을 설정으로 뺐다</h3>
 * 프런트엔드 주소는 환경마다 다르므로 코드에 박지 않는다.
 * {@code security.cors.allowed-origins}에 콤마로 나열하며 기본값은 로컬 개발용 주소다.
 * 운영에서는 환경변수 {@code CORS_ALLOWED_ORIGINS}로 실제 도메인을 준다.
 *
 * <h3>{@code PATCH}를 추가했다 — 빠져 있었다</h3>
 * {@code @PatchMapping}이 세 곳에 있는데({@code AbsenceAdminController}의 승인·반려,
 * {@code ChecklistUserController}의 항목 체크) 허용 메서드 목록에 없었다.
 * <b>브라우저에서 크로스 오리진으로 호출하면 preflight 단계에서 막힌다.</b>
 * 지금까지 드러나지 않은 것은 오리진이 전부 허용이라 preflight를 제대로 겪지 않는 경로로
 * 개발해왔기 때문으로 보인다. <b>보안 설정을 조이자 기능 결함이 함께 드러난 사례다.</b>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${security.cors.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
                .allowedHeaders("*")
                .allowCredentials(true)
                // preflight 응답 캐시. 매 요청마다 OPTIONS가 오가는 것을 줄인다.
                .maxAge(3600);
    }
}
