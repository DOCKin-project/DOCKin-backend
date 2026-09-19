package com.DOCKin.global.security.config;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검증: {@code /api/*}{@code /admin/**}는 경로 하나로 막힌다 (백로그 P2-18-6).
 *
 * <p>서비스마다 손으로 {@code role != ADMIN}을 검사하는 관례는 메서드 하나 빠지면 그대로 구멍이었다.
 * 처음엔 세 경로가 실제로 그랬다 — 컨트롤러가 principal을 받지 않고 서비스도 검사하지 않아
 * 일반 사용자에게 열려 있었다. 그래서 <b>서비스 검사가 없는 경로</b>로 시험한다:
 * 여기서 403이 나오면 그것은 {@code SecurityConfig}의 경로 규칙이 잡은 것이다.
 *
 * <p>안전교육 관리자 읽기 둘은 2026-09-17에 사라졌다(P2-20-5, user 경로와 완전한 복제라 지웠다).
 * 남은 것은 체크리스트 상세 하나다. 관리자 통과 검사는 <b>403이 아니면</b> 통과로 본다 — 200이든
 * 404든 서비스까지 간 것이고, 경로 규칙이 관리자까지 막았다면 서비스에 닿기 전에 403이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("관리자 API - 경로 하나로 막힌다")
class AdminPathSecurityTest extends ContainerTestSupport {

    /** 서비스에 ADMIN 검사가 없던 읽기 경로. 경로 규칙이 없으면 일반 사용자에게 200이 나간다. */
    private static final String[] PREVIOUSLY_OPEN = {
            "/api/checklist/admin/checklists/1",
    };

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("익명은 401")
    void anonymous() throws Exception {
        for (String path : PREVIOUSLY_OPEN) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("일반 사용자는 403 - 서비스가 검사하지 않던 경로에서도")
    void ordinaryUser() throws Exception {
        for (String path : PREVIOUSLY_OPEN) {
            mockMvc.perform(get(path)).andExpect(status().isForbidden());
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("관리자는 통과한다 - 경로 규칙이 관리자까지 막으면 안 된다")
    void admin() throws Exception {
        // 경로 규칙이 관리자를 막았다면 서비스에 닿기 전에 403이다. 200이든 404든(컨테이너 DB를 다른
        // 테스트와 공유하므로 1번이 있을 수도 없을 수도) 서비스까지 갔다는 뜻이라 둘 다 통과로 본다.
        mockMvc.perform(get("/api/checklist/admin/checklists/1"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 404));
    }
}
