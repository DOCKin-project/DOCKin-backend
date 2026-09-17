package com.DOCKin.global.security.config;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검증: {@code /api/member/{login,signup,refresh}}가 토큰 없이 열린다 (P2-20-4).
 *
 * <p>컨트롤러에 {@code /api/member}를 더하는 것과 화이트리스트에 더하는 것은 다른 파일이다.
 * 후자를 빠뜨리면 경로는 있는데 익명이 401을 받는다 — 로그인을 하려면 로그인이 필요한 상태.
 * 그래서 실제 시큐리티 체인으로 본다. 빈 본문을 보내 <b>401이 아니라 400</b>이 나오면 화이트리스트를
 * 통과해 컨트롤러의 {@code @Valid}까지 간 것이다. 로그아웃·탈퇴는 열리면 안 되므로 401을 단언한다.
 *
 * <p>옛 경로 {@code /member/*}는 앱이 옮겨 갈 때까지 같이 열려 있다. 그것도 본다 — 별칭을 지울 때
 * {@code LEGACY_OPEN}과 화이트리스트 항목을 함께 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("회원 경로 화이트리스트 - /api/member도 토큰 없이 열린다")
class MemberPathWhitelistTest extends ContainerTestSupport {

    private static final String[] OPEN = {
            "/api/member/login", "/api/member/signup", "/api/member/refresh",
    };
    private static final String[] LEGACY_OPEN = {
            "/member/login", "/member/signup", "/member/refresh",
    };
    private static final String[] CLOSED = {
            "/api/member/logout", "/member/logout",
    };

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("login·signup·refresh - 익명이 400을 받는다 (401이면 화이트리스트에 없는 것)")
    void openPaths() throws Exception {
        for (String path : OPEN) {
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("옛 경로 /member/* - 앱이 옮길 때까지 같이 열려 있다")
    void legacyOpenPaths() throws Exception {
        for (String path : LEGACY_OPEN) {
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("logout - 두 경로 다 익명은 401. 프리픽스를 더하며 열리면 안 되는 것까지 열리지 않았다")
    void closedPaths() throws Exception {
        for (String path : CLOSED) {
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
