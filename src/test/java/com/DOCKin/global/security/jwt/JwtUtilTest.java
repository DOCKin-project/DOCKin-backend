package com.DOCKin.global.security.jwt;

import com.DOCKin.member.dto.CustomUserInfoDto;
import com.DOCKin.member.model.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파서를 생성자에서 한 번만 만들고(#162) 재사용해도 검증·subject·만료 처리가 그대로인지. 만든 토큰을 같은
 * 인스턴스로 여러 번 읽는 것이 곧 재사용 검사다 — 전에는 호출마다 새 파서였다.
 */
class JwtUtilTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "jwt-util-test-secret-key-must-be-at-least-32-bytes".getBytes());

    private static CustomUserInfoDto worker() {
        return CustomUserInfoDto.builder().userId("worker01").name("김철수").role(UserRole.USER).build();
    }

    @Test
    @DisplayName("한 파서로 여러 토큰을 검증하고 subject를 꺼낸다")
    void reusedParserValidatesAndReadsSubject() {
        JwtUtil util = new JwtUtil(SECRET, 3600, 86400);
        String access = util.createAccessToken(worker());
        String refresh = util.createRefreshToken(worker());

        for (int i = 0; i < 3; i++) {
            assertThat(util.isValidToken(access)).isTrue();
            assertThat(util.getUserId(access)).isEqualTo("worker01");
            assertThat(util.isValidToken(refresh)).isTrue();
        }
        assertThat(util.getExpiration(refresh)).isGreaterThan(util.getExpiration(access));
    }

    @Test
    @DisplayName("만료된 토큰은 isValidToken이 false, parseClaims는 클레임을 그대로 돌려준다")
    void expiredTokenStillYieldsClaims() {
        JwtUtil util = new JwtUtil(SECRET, -60, -60);
        String expired = util.createAccessToken(worker());

        assertThat(util.isValidToken(expired)).isFalse();
        assertThat(util.parseClaims(expired).getSubject()).isEqualTo("worker01");
    }
}
