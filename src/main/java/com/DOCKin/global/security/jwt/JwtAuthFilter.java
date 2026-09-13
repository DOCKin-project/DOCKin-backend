package com.DOCKin.global.security.jwt;

import com.DOCKin.member.service.CustomUserDetailsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final CustomUserDetailsService customUserDetailsService;
    private final JwtUtil jwtUtil;
    private final JwtBlacklist jwtBlacklist;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/ws")) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        // 여기는 이전에 authHeader를 값 그대로 INFO로 찍고 있었다.
        // 즉 모든 요청마다 JWT 원문이 로그에 남았다 -- 로그 파일을 읽을 수 있으면
        // 만료 전까지 아무 사용자로든 로그인할 수 있다는 뜻이다.
        // 토큰은 비밀번호와 같은 급이므로 값이 아니라 "있었는가"만 남긴다.
        //
        // 수준도 debug로 내렸다. P2-11-2에서 붙인 헬스체크가 10초마다 두드리므로
        // 요청 단위 INFO를 유지하면 로그가 헬스체크로 뒤덮인다.
        log.debug("요청: {} (Authorization {})", path, authHeader != null ? "있음" : "없음");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            try {
                if (jwtUtil.isValidToken(token) && !jwtBlacklist.isBlacklisted(token)) {
                    String userId = jwtUtil.getUserId(token);

                    if (userId != null) {
                        UserDetails userDetails = customUserDetailsService.loadUserByUsername(userId);

                        if (userDetails != null) {
                            UsernamePasswordAuthenticationToken auth =
                                    new UsernamePasswordAuthenticationToken(
                                            userDetails,
                                            null,
                                            userDetails.getAuthorities()
                                    );
                            SecurityContextHolder.getContext().setAuthentication(auth);
                            log.debug("인증 성공: user_id = {}", userId);
                        }
                    } else {
                        log.warn("토큰에서 userId 추출 실패");
                    }
                } else {
                    log.warn("유효하지 않은 토큰이거나 블랙리스트에 등록된 토큰입니다.");
                }
            } catch (Exception e) {
                log.error("JWT 인증 에러: {}", e.getMessage());
            }
        } else {
            // warn이 아니라 debug다. 토큰 없는 요청은 비정상이 아니다 --
            // 로그인·회원가입·정적 리소스·헬스체크가 전부 여기로 온다.
            // warn으로 두면 정상 트래픽에 경고가 쌓이고, 그러면 사람이 경고를 안 보게 된다.
            log.debug("Authorization 헤더가 없거나 형식이 잘못되었습니다.");
        }

        filterChain.doFilter(request, response);
    }
}