package com.DOCKin.DOCKin_spring.service;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.dto.LogOutRequestDto;
import com.DOCKin.member.dto.LoginResponseDto;
import com.DOCKin.member.model.RefreshToken;
import com.DOCKin.member.dto.MemberRequestDto;
import com.DOCKin.member.model.UserRole;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.DOCKin.global.security.jwt.JwtBlacklist;
import com.DOCKin.global.security.jwt.JwtUtil;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.member.repository.RefreshTokenRepository;
import com.DOCKin.member.service.MemberService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class) // 가짜 객체(Mock)를 사용하기 위한 설정
public class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository; // 가짜 리포지토리

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private JwtBlacklist jwtBlacklist;

    @Mock
    private RefreshTokenRepository refreshTokenRepository; // 가짜 리포지토리

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private MemberService memberService; // 위 가짜 객체들을 주입받은 서비스

    @Test
    @DisplayName("회원 탈퇴 시 회원 정보와 리프레시 토큰이 모두 삭제되어야 한다")
    void deleteAccountTest() {
        // 1. 준비 (given)
        String userId = "testUser";
        Member member = Member.builder().userId(userId).build();

        // memberRepository가 해당 ID를 찾으면 가짜 member 객체를 반환하도록 설정
        when(memberRepository.findByUserId(userId)).thenReturn(Optional.of(member));

        // 2. 실행 (when) - 본인이 본인을 지운다
        memberService.deleteAccount(userId, userId);

        // 3. 검증 (then)
        // memberRepository의 delete 메서드가 한 번 호출되었는지 확인
        verify(memberRepository, times(1)).delete(any(Member.class));

        // refreshTokenRepository의 deleteByUserId 메서드가 해당 ID로 호출되었는지 확인
        verify(refreshTokenRepository, times(1)).deleteByUserId(userId);
    }

    @Test
    @DisplayName("남의 계정은 지울 수 없다 - 존재 여부를 묻기도 전에 거부한다 (P2-18-2)")
    void deleteAccount_남의_계정() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> memberService.deleteAccount("victim", "attacker"));

        assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
        // 조회조차 하지 않는다 - "없는 사용자"와 "있는 사용자"를 다르게 답하면 계정 목록을 캐는 데 쓰인다.
        verify(memberRepository, never()).findByUserId(any());
        verify(memberRepository, never()).delete(any(Member.class));
        verify(refreshTokenRepository, never()).deleteByUserId(any());
    }

    @Test
    @DisplayName("가입자는 무엇을 보내든 USER다 - 요청 본문으로 ADMIN이 되지 않는다 (P2-18-1)")
    void signup_권한은_서버가_정한다() {
        // MemberRequestDto에는 role 필드가 없다. 그래도 서비스가 무엇을 저장하는지 직접 본다 -
        // 필드가 나중에 되살아나도 이 테스트가 잡는다.
        MemberRequestDto dto = new MemberRequestDto("newbie", "이름", "pw", "ko", true, "제1조선소", null);
        when(memberRepository.existsById("newbie")).thenReturn(false);
        when(passwordEncoder.encode("pw")).thenReturn("encoded");

        memberService.signup(dto);

        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(saved.capture());
        assertEquals(UserRole.USER, saved.getValue().getRole());
        assertEquals("encoded", saved.getValue().getPassword());
    }

    @Test
    @DisplayName("갱신 - 저장된 리프레시 토큰과 같으면 새 토큰 한 쌍을 주고 옛 것은 덮어쓴다 (P2-18-5)")
    void refresh_회전() {
        Member member = Member.builder().userId("u1").name("이름").role(UserRole.USER).build();
        when(jwtUtil.isValidToken("old-refresh")).thenReturn(true);
        when(jwtUtil.getUserId("old-refresh")).thenReturn("u1");
        when(refreshTokenRepository.findById("u1"))
                .thenReturn(Optional.of(RefreshToken.builder().userId("u1").token("old-refresh").build()));
        when(memberRepository.findByUserId("u1")).thenReturn(Optional.of(member));
        when(jwtUtil.createAccessToken(any())).thenReturn("new-access");
        when(jwtUtil.createRefreshToken(any())).thenReturn("new-refresh");

        LoginResponseDto out = memberService.refresh("old-refresh");

        assertEquals("new-access", out.getAccessToken());
        assertEquals("new-refresh", out.getRefreshToken());
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        assertEquals("new-refresh", saved.getValue().getToken(), "옛 리프레시 토큰이 남아 있으면 회전이 아니다");
    }

    @Test
    @DisplayName("갱신 - 서명은 맞는데 저장된 것과 다르면(액세스 토큰이거나 회전된 옛 토큰) 폐기하고 거부한다")
    void refresh_재사용_감지() {
        when(jwtUtil.isValidToken("stale")).thenReturn(true);
        when(jwtUtil.getUserId("stale")).thenReturn("u1");
        when(refreshTokenRepository.findById("u1"))
                .thenReturn(Optional.of(RefreshToken.builder().userId("u1").token("current").build()));

        BusinessException e = assertThrows(BusinessException.class, () -> memberService.refresh("stale"));

        assertEquals(ErrorCode.INVALID_TOKEN, e.getErrorCode());
        // 도둑과 주인 중 누가 진짜인지 모르므로 둘 다 끊는다.
        verify(refreshTokenRepository).deleteByUserId("u1");
        verify(jwtUtil, never()).createAccessToken(any());
    }

    @Test
    @DisplayName("갱신 - 서명이 틀리거나 만료된 리프레시 토큰은 DB를 묻지 않고 거부한다")
    void refresh_무효_토큰() {
        when(jwtUtil.isValidToken("garbage")).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class, () -> memberService.refresh("garbage"));

        assertEquals(ErrorCode.INVALID_TOKEN, e.getErrorCode());
        verify(refreshTokenRepository, never()).findById(any());
    }

    @Test
    @DisplayName("로그아웃 성공 - 리프레시 토큰 삭제 및 블랙리스트 등록")
    void logout_success() {
        // given
        String accessToken = "Bearer valid-access-token";
        String refreshToken = "valid-refresh-token";
        String pureToken = "valid-access-token";
        long expiration = 1000L;

        LogOutRequestDto dto = LogOutRequestDto.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();

        // Mock 설정: jwtUtil.getExpiration이 호출되면 1000L을 반환해라
        when(jwtUtil.getExpiration(pureToken)).thenReturn(expiration);

        // when
        memberService.logout(dto);

        // then
        // 1. 리프레시 토큰이 삭제되었는지 검증
        verify(refreshTokenRepository, times(1)).deleteByToken(refreshToken);

        // 2. 블랙리스트에 순수 토큰(Bearer 제외)과 만료시간이 저장되었는지 검증
        verify(jwtBlacklist, times(1)).add(pureToken, expiration);
    }
}