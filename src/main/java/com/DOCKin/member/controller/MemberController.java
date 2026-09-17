package com.DOCKin.member.controller;

import com.DOCKin.member.dto.LogOutRequestDto;
import com.DOCKin.member.dto.LoginRequestDto;
import com.DOCKin.member.dto.LoginResponseDto;
import com.DOCKin.member.dto.MemberRequestDto;
import com.DOCKin.member.dto.RefreshRequestDto;
import com.DOCKin.member.dto.SignupResponseDto;
import com.DOCKin.member.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.DOCKin.global.security.auth.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 경로가 둘이다 — {@code /api/member}가 정식, {@code /member}는 앱이 옮겨 갈 때까지의 별칭 (P2-20-4).
 *
 * <p>다른 컨트롤러는 전부 {@code /api/…}인데 이것만 아니었다. 경로를 바꾸는 건 앱 쪽 계약이라
 * 한 번에 끊지 않고 둘 다 받는다. 앱이 {@code /api/member}로 옮기면 {@code /member}와
 * 화이트리스트의 옛 항목 셋을 지운다 — 그때까지 {@code MemberApiContractTest}가 두 경로 모두를 검사한다.
 */
@Tag(name="인증/인가", description="로그인/회원가입")
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/member", "/member"})
public class MemberController {
    private final MemberService memberService;

    @Operation(summary="로그인",description = "로그인을 할 수 있음")
    @PostMapping("/login")
    public ResponseEntity<LoginResponseDto> getMemberProfile(
            @Valid @RequestBody LoginRequestDto request){
        LoginResponseDto response = memberService.login(request);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @Operation(summary="토큰 갱신", description = "리프레시 토큰으로 새 액세스·리프레시 토큰을 받는다. 낸 리프레시 토큰은 무효가 된다")
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponseDto> refresh(@Valid @RequestBody RefreshRequestDto dto){
        return ResponseEntity.ok(memberService.refresh(dto.getRefreshToken()));
    }

    @Operation(summary="로그아웃",description = "로그아웃을 할 수 있음")
    @PostMapping("/logout")
    public ResponseEntity<Void> Logout(@Valid @RequestBody LogOutRequestDto dto){
        memberService.logout(dto);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary="회원가입",description = "회원가입을 할 수 있음. 201과 가입된 사원번호를 JSON으로")
    @PostMapping("/signup")
    public ResponseEntity<SignupResponseDto> signup(@Valid @RequestBody MemberRequestDto dto) {
        // 200 + text/plain 문자열이던 것을 다른 생성 API와 같은 201 + JSON으로(P2-20-4).
        String id = memberService.signup(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(new SignupResponseDto(id));
    }

    @Operation(summary="회원탈퇴", description = "회원탈퇴를 할 수 있음")
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deleteMember(@PathVariable("userId") String userId,
                                             @AuthenticationPrincipal CustomUserDetails customUserDetails){
        // 경로의 userId만 믿으면 남의 계정을 지운다(P2-18-2). 인증 주체와 대조한다.
        memberService.deleteAccount(userId, customUserDetails.getMember().getUserId());
        return ResponseEntity.noContent().build();
    }

    }

