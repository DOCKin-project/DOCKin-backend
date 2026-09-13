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
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService{
    private final JwtUtil jwtUtil;
    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtBlacklist jwtBlacklist;

    /** 없는 사용자에게 돌릴 bcrypt 해시. 값은 무의미하고 비용만 같으면 된다("dummy"의 해시). */
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5XkfM9x1Sgb4c5Z0zVfLg5T5Yqz0K";
    //로그인 로직
    @Transactional
    public LoginResponseDto login(LoginRequestDto dto){
        // "없는 사원번호"와 "틀린 비밀번호"를 같은 답으로 돌려준다. 다르게 답하면 로그인 창이
        // 사원번호 목록을 확인해 주는 도구가 된다(P2-18-8). 없는 사용자에게도 bcrypt를 한 번 돌려
        // 응답 시간으로도 가르지 못하게 한다 — 그래서 orElseThrow가 아니라 map이다.
        Member member = memberRepository.findByUserId(dto.getUserId()).orElse(null);
        String storedHash = member != null ? member.getPassword() : DUMMY_HASH;
        boolean matches = passwordEncoder.matches(dto.getPassword(), storedHash);
        if (member == null || !matches) {
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

    /**
     * 액세스 토큰 갱신. <b>리프레시 토큰은 한 번 쓰면 버린다(회전).</b>
     *
     * <p>이전에는 이 경로가 없었다(백로그 P2-18-5). 로그인이 리프레시 토큰을 저장까지 해 두고
     * 아무도 쓰지 않아, 액세스 토큰이 만료되면 재로그인뿐이었다.
     *
     * <h3>서명만으로는 부족하다 — DB에 있는 것과 같아야 한다</h3>
     * 액세스 토큰과 리프레시 토큰은 클레임 모양이 같다({@code JwtUtil.createToken}). 서명만 보면
     * <b>액세스 토큰을 리프레시 토큰 자리에 넣어도 통과한다.</b> 그래서 {@code refresh_token} 테이블에
     * 저장된 것과 원문이 같은지까지 본다. 액세스 토큰은 거기 없다.
     *
     * <h3>재사용은 탈취로 본다</h3>
     * 서명은 유효한데 저장된 것과 다르면, 이미 회전돼 버린 옛 토큰이다. 정상 클라이언트는 옛 것을
     * 다시 낼 일이 없으므로 누군가 복사해 둔 것이다. 그 사용자의 리프레시 토큰을 지워 양쪽 다
     * 다시 로그인하게 한다 — 도둑과 주인 중 누가 진짜인지 서버는 모르고, 둘 다 끊는 것이 안전하다.
     */
    @Transactional
    public LoginResponseDto refresh(String refreshToken) {
        if (!jwtUtil.isValidToken(refreshToken)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        String userId = jwtUtil.getUserId(refreshToken);

        RefreshToken stored = refreshTokenRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));
        if (!stored.getToken().equals(refreshToken)) {
            log.warn("리프레시 토큰 재사용 감지 - 사용자의 토큰을 폐기합니다. userId={}", userId);
            refreshTokenRepository.deleteByUserId(userId);
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        CustomUserInfoDto info = CustomUserInfoDto.builder()
                .userId(member.getUserId())
                .name(member.getName())
                .role(member.getRole())
                .build();

        String newAccessToken = jwtUtil.createAccessToken(info);
        String newRefreshToken = jwtUtil.createRefreshToken(info);
        // PK가 user_id라 save가 덮어쓴다 - 사용자당 리프레시 토큰은 하나다.
        refreshTokenRepository.save(RefreshToken.builder()
                .userId(userId)
                .token(newRefreshToken)
                .build());
        return new LoginResponseDto(newAccessToken, newRefreshToken, member.getName(), member.getRole());
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