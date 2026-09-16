package com.DOCKin.global.security.jwt;

import com.DOCKin.member.service.CustomUserDetailsService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.client.RedisConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 검증: 블랙리스트 조회가 예외로 끝나면 필터가 인증을 <b>세우지 않는다</b> — Redis 장애 시 닫힘의
 * 필터 쪽 절반 (ADR-0009 2절 셋째 행). Redis 쪽 절반(죽으면 예외를 던진다)은 {@code JwtBlacklistRedisTest}.
 *
 * <p>둘을 이어 보면: Redis 죽음 → {@code isBlacklisted}가 던짐 → 여기서 잡고 인증 없이 체인 진행 →
 * {@code CustomAuthenticationEntryPoint}가 401. 유효한 토큰이 인증을 세우는 대조군을 같이 둔다 —
 * 그게 없으면 "예외 경로에서 인증이 없다"는 단언이 "언제나 없다"와 구분이 안 된다.
 */
class JwtAuthFilterTest {

    private final CustomUserDetailsService userDetailsService = mock(CustomUserDetailsService.class);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final JwtBlacklist blacklist = mock(JwtBlacklist.class);
    private final FilterChain chain = mock(FilterChain.class);

    private final JwtAuthFilter filter = new JwtAuthFilter(userDetailsService, jwtUtil, blacklist);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest requestWith(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/worklog");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    @Test
    @DisplayName("대조군: 유효하고 블랙리스트에 없는 토큰은 인증을 세운다")
    void 정상() throws Exception {
        when(jwtUtil.isValidToken("t")).thenReturn(true);
        when(blacklist.isBlacklisted("t")).thenReturn(false);
        when(jwtUtil.getUserId("t")).thenReturn("u1");
        when(userDetailsService.loadUserByUsername("u1"))
                .thenReturn(User.withUsername("u1").password("").roles("USER").build());

        filter.doFilter(requestWith("t"), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("u1");
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("블랙리스트 조회가 예외로 끝나면 인증을 세우지 않는다 - Redis 장애는 401이지 통과가 아니다")
    void 블랙리스트_예외면_인증_없음() throws Exception {
        when(jwtUtil.isValidToken("t")).thenReturn(true);
        when(blacklist.isBlacklisted("t")).thenThrow(new RedisConnectionException("Unable to connect"));

        filter.doFilter(requestWith("t"), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // 필터가 여기서 응답을 끊지 않는다. 체인은 이어지고 401은 엔트리포인트 몫이다 —
        // 그래야 permitAll 경로(로그인·헬스체크)는 Redis가 죽어도 열려 있다.
        verify(chain).doFilter(any(), any());
    }
}
