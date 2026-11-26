package com.DOCKin.member.service;

import com.DOCKin.member.dto.CustomUserInfoDto;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.security.auth.CustomUserDetails;
import com.DOCKin.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

//로그인 인증 로직
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
    private final MemberRepository memberRepository;

    @Override
    public UserDetails loadUserByUsername(String userId) throws UsernameNotFoundException {
        // 탈퇴 회원은 없는 사용자다(#100). JwtAuthFilter가 요청마다 여기를 거치므로, 탈퇴 전에 받은 액세스 토큰도
        // 만료를 기다리지 않고 바로 인증에 실패한다 — 블랙리스트에 넣을 토큰 원문이 서비스에 없어도 된다.
        return memberRepository.findByUserId(userId)
                .filter(member -> !member.isDeleted())
                .map(member->{
                    CustomUserInfoDto infoDto = new CustomUserInfoDto(
                            member.getUserId(),
                            member.getPassword(),
                            member.getName(),
                            member.getRole()
                    );
                    return new CustomUserDetails(infoDto);
                })
                .orElseThrow(()-> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

}
