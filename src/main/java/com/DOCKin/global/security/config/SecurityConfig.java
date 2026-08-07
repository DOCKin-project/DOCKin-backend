package com.DOCKin.global.security.config;

import com.DOCKin.global.security.jwt.JwtAuthFilter;
import com.DOCKin.global.security.jwt.JwtBlacklist;
import com.DOCKin.global.security.jwt.JwtUtil;
import com.DOCKin.member.service.CustomUserDetailsService;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@AllArgsConstructor
public class SecurityConfig {
    private final CustomUserDetailsService customUserDetailsService;
    private final JwtUtil jwtUtil;
    private final CustomAccessDeniedHandler accessDeniedHandler;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final SecurityPathConfig securityPathConfig;

    @PostConstruct
    public void setupSecurityContext() {
        // 비동기 스레드(워커 스레드)로 SecurityContext를 전파하는 설정
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtBlacklist jwtBlacklist) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        http.authorizeHttpRequests(authorize -> authorize
                // 1. AI 관련 경로를 최상단에 배치
                .requestMatchers("/api/ai/**").authenticated()

                // 1-1. Actuator (P2-11-2). 화이트리스트보다 앞에 둔다 -- 순서가 곧 우선순위다.
                //
                // health만 공개한다. 로드밸런서와 compose 헬스체크가 토큰 없이 닿아야 하는 유일한
                // 엔드포인트이고, 익명에게는 UP/DOWN만 나간다(show-details=when-authorized).
                //
                // 나머지는 ADMIN만 본다. metrics에는 힙·GC·커넥션 풀 수치가 그대로 있어
                // 서버 상태를 밖에서 읽을 수 있고, info에는 빌드 버전과 커밋 해시가 들어간다.
                // 공개하면 "어느 버전이 돌고 있는지"를 공격자가 먼저 알게 된다.
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/**").hasRole("ADMIN")

                // 2. 화이트리스트 (배열을 그대로 전달)
                .requestMatchers(securityPathConfig.getWhiteListArray()).permitAll()

                // 3. 그 외 모든 요청 인증 필요
                .anyRequest().authenticated());

        http.securityContext(context -> context
                .requireExplicitSave(false));


        // JWT 필터 추가
        http.addFilterBefore(new JwtAuthFilter(customUserDetailsService, jwtUtil, jwtBlacklist),
                UsernamePasswordAuthenticationFilter.class);

        http.exceptionHandling(exception -> exception
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }
}