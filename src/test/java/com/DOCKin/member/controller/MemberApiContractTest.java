package com.DOCKin.member.controller;

import com.DOCKin.member.service.MemberService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검증: 회원 API의 응답 계약 (P2-20-4).
 *
 * <h3>있었던 상태</h3>
 * {@code POST /member/signup}만 다른 생성 API와 달랐다 — 201이 아니라 200, 본문이 JSON이 아니라
 * {@code text/plain} 사원번호 문자열. 클라이언트가 이 하나만 다르게 파싱해야 했다. 그리고 경로에
 * {@code /api} 프리픽스가 없는 컨트롤러가 이것뿐이었다.
 *
 * <h3>경로가 둘인 동안의 검사</h3>
 * {@code /api/member}가 정식이고 {@code /member}는 앱이 옮겨 갈 때까지의 별칭이다. 둘 다 같은 핸들러로
 * 가는지 여기서 본다. 별칭을 지울 때 이 테스트의 {@code legacyPath}도 함께 지운다 — 그게 이 테스트의
 * 수명이다. 화이트리스트(토큰 없이 열리는지)는 시큐리티 체인이 필요하므로 {@code MemberPathWhitelistTest}가 본다.
 */
@WebMvcTest(MemberController.class)
@WithMockUser
@DisplayName("회원 API 계약 - signup은 201 + JSON, 경로는 /api/member")
class MemberApiContractTest {

    private static final String SIGNUP_BODY = """
            {"userId":"E2026001","name":"홍길동","password":"pw1234!"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberService memberService;

    @Test
    @DisplayName("signup - 201이고 본문은 {userId} JSON이다. 200 + text/plain이 아니다")
    void signupReturns201Json() throws Exception {
        when(memberService.signup(any())).thenReturn("E2026001");

        mockMvc.perform(post("/api/member/signup").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(SIGNUP_BODY))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").value("E2026001"));
    }

    @Test
    @DisplayName("옛 경로 /member도 같은 곳으로 - 앱이 옮길 때까지")
    void legacyPath() throws Exception {
        when(memberService.signup(any())).thenReturn("E2026001");

        mockMvc.perform(post("/member/signup").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(SIGNUP_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("E2026001"));
    }

    @Test
    @DisplayName("signup 검증 실패는 여전히 400 - 계약을 바꾸며 예외 경로를 건드리지 않았다")
    void signupValidationStill400() throws Exception {
        mockMvc.perform(post("/api/member/signup").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
