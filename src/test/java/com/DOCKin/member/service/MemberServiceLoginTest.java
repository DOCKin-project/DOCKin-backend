package com.DOCKin.member.service;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.security.jwt.JwtBlacklist;
import com.DOCKin.global.security.jwt.JwtUtil;
import com.DOCKin.member.dto.LoginRequestDto;
import com.DOCKin.member.login.LoginAttempts;
import com.DOCKin.member.login.LoginAttemptsExceededException;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.member.repository.RefreshTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 검증: 로그인이 시도 제한을 <b>어느 순서로</b> 부르는가 (P2-18-12). 카운터 자체는 {@code LoginAttemptsRedisTest}.
 *
 * <ul>
 *   <li>막혀 있으면 bcrypt를 태우지 않는다 — 태우면 제한이 CPU 소모의 도구가 된다</li>
 *   <li>틀리면 센다. 없는 사원번호도 센다 — 응답도 카운터도 존재 여부를 가르지 않는다(P2-18-8)</li>
 *   <li>맞으면 지운다</li>
 * </ul>
 */
class MemberServiceLoginTest {

    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final LoginAttempts loginAttempts = mock(LoginAttempts.class);

    private final MemberService service = new MemberService(jwtUtil, memberRepository, refreshTokenRepository,
            passwordEncoder, mock(JwtBlacklist.class), loginAttempts);

    private static final Member U1 = Member.builder().userId("u1").name("홍길동").password("$hash").role(UserRole.USER).build();

    @Test
    @DisplayName("막혀 있으면 비밀번호를 대조하지 않는다 - 맞는 비밀번호여도 429")
    void 막히면_bcrypt_없음() {
        doThrow(new LoginAttemptsExceededException(600)).when(loginAttempts).check("u1");
        when(memberRepository.findByUserId("u1")).thenReturn(Optional.of(U1));

        assertThatThrownBy(() -> service.login(new LoginRequestDto("u1", "right")))
                .isInstanceOf(LoginAttemptsExceededException.class);

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(loginAttempts, never()).failed(anyString());
    }

    @Test
    @DisplayName("틀리면 센다 - 응답은 그대로 LOGIN_INPUT_INVALID")
    void 틀리면_센다() {
        when(memberRepository.findByUserId("u1")).thenReturn(Optional.of(U1));
        when(passwordEncoder.matches("wrong", "$hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequestDto("u1", "wrong")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.LOGIN_INPUT_INVALID));

        verify(loginAttempts).failed("u1");
        verify(loginAttempts, never()).succeeded(anyString());
    }

    @Test
    @DisplayName("없는 사원번호도 센다 - 카운터로도 존재 여부가 새지 않는다")
    void 없는_사원번호도_센다() {
        when(memberRepository.findByUserId("nobody")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequestDto("nobody", "x")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.LOGIN_INPUT_INVALID));

        verify(loginAttempts).check("nobody");
        verify(loginAttempts).failed("nobody");
    }

    @Test
    @DisplayName("맞으면 지운다")
    void 맞으면_지운다() {
        when(memberRepository.findByUserId("u1")).thenReturn(Optional.of(U1));
        when(passwordEncoder.matches("right", "$hash")).thenReturn(true);
        when(jwtUtil.createAccessToken(any())).thenReturn("access");
        when(jwtUtil.createRefreshToken(any())).thenReturn("refresh");

        service.login(new LoginRequestDto("u1", "right"));

        verify(loginAttempts).succeeded("u1");
        verify(loginAttempts, never()).failed(anyString());
    }
}
