package com.DOCKin.member.service;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.security.jwt.JwtBlacklist;
import com.DOCKin.global.security.jwt.JwtUtil;
import com.DOCKin.member.dto.*;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.RefreshToken;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.model.WorkShift;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.member.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService{
    private final JwtUtil jwtUtil;
    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtBlacklist jwtBlacklist;
    //로그인 로직
    @Transactional
    public LoginResponseDto login(LoginRequestDto dto){
        Member member = memberRepository.findByUserId(dto.getUserId()).
                orElseThrow(()-> new BusinessException(ErrorCode.USER_NOT_FOUND));

    if(!passwordEncoder.matches(dto.getPassword(), member.getPassword())){
        throw new BusinessException(ErrorCode.LOGIN_INPUT_INVALID);
    }
        CustomUserInfoDto info = CustomUserInfoDto.builder()
                .userId(member.getUserId())
                .name(member.getName())
                .role(member.getRole())
                .build();

    String accessToken=jwtUtil.createAccessToken(info);
    String refreshToken=jwtUtil.createRefreshToken(info);
    String name = member.getName();
        UserRole role = member.getRole();

        RefreshToken refreshTokenEntity= RefreshToken.builder()
                .userId(member.getUserId())
                .token(refreshToken)
                .build();
        refreshTokenRepository.save(refreshTokenEntity);
    return new LoginResponseDto(accessToken,refreshToken,name,role);
    }

    //로그아웃 로직
    @Transactional
    public void logout(LogOutRequestDto dto){
        refreshTokenRepository.deleteByToken(dto.getRefreshToken());
        String token = dto.getAccessToken();
        if(token.startsWith("Bearer ")){
            token = token.substring(7);
        }
        long expiration = jwtUtil.getExpiration(token);
        jwtBlacklist.add(token,expiration);
    }

    //회원가입 로직
    @Transactional
    public String signup(MemberRequestDto dto) {
        if(memberRepository.existsById(dto.getUserId())) {
            throw new BusinessException(ErrorCode.USERID_DUPLICATION);
        }
        String encodedPassword = passwordEncoder.encode(dto.getPassword());
        Member member = Member.builder()
                .userId(dto.getUserId())
                .name(dto.getName())
                .password(encodedPassword)
                // 가입자는 언제나 USER다. 요청 본문의 값을 믿으면 누구나 ADMIN으로 가입한다(P2-18-1).
                .role(UserRole.USER)
                .language_code(dto.getLanguage_code())
                .tts_enabled(dto.getTts_enabled())
                .shipYardArea(dto.getShipYardArea())
                .workShift(dto.getWorkShift() != null ? dto.getWorkShift() : WorkShift.MORNING)
                .build();
        memberRepository.save(member);
        return member.getUserId();
    }

    /**
     * 회원탈퇴. <b>본인만.</b>
     *
     * <p>이전에는 경로 변수의 userId를 그대로 지웠다 — 인증만 있으면 남의 계정을 탈퇴시킬 수
     * 있었다(P2-18-2). 존재 여부보다 먼저 본인인지를 본다: 남의 ID로 왔을 때 "없는 사용자"와
     * "있는 사용자"를 다르게 답하면 계정 목록을 캐는 데 쓰인다.
     *
     * @param userId      지우려는 계정 (경로 변수)
     * @param requesterId 요청한 사람 (인증 주체)
     */
    @Transactional
    public void deleteAccount(String userId, String requesterId){
        if (!userId.equals(requesterId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(()-> new BusinessException(ErrorCode.USER_NOT_FOUND));
        memberRepository.delete(member);
        refreshTokenRepository.deleteByUserId(userId);
    }
}