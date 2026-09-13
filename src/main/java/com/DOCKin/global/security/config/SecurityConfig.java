package com.DOCKin.global.security.config;

import com.DOCKin.global.security.jwt.JwtAuthFilter;
import com.DOCKin.global.security.jwt.JwtBlacklist;
import com.DOCKin.global.security.jwt.JwtUtil;
import com.DOCKin.member.service.CustomUserDetailsService;
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

    // MODE_INHERITABLETHREADLOCAL은 여기 없다(P2-18-11). 그 전략은 풀 스레드가 "만들어질 때"
    // 부모의 SecurityContext를 복사해 그 사용자를 계속 든다 -- 나중에 다른 사용자의 작업을 그
    // 스레드가 집으면 앞 사용자로 실행된다. 비동기 전파는 AsyncConfig의 TaskDecorator가
    // 작업 단위로 한다.

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

                // 1-2. 관리자 API (P2-18-6). 서비스마다 손으로 role != ADMIN을 검사하는 관례는
                // 메서드 하나 빠지면 그대로 구멍이다 -- SafetyAdminController의 읽기 셋과
                // ChecklistAdminController의 상세 조회가 실제로 그랬다. 경로 하나로 막는다.
                // 서비스의 수동 검사는 그대로 둔다: 두 겹이 한 겹보다 낫고, 어느 쪽이 먼저 걸리든 같은 답이다.
                .requestMatchers("/api/*/admin/**").hasRole("ADMIN")

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