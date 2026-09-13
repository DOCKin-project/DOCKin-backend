package com.DOCKin.global.config;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Spring Boot 4에서 테스트 자동 구성이 기술별 모듈로 쪼개졌다.
// org.springframework.boot.test.autoconfigure.web.servlet 아래에 있던 것이 여기로 옮겨졌다
// (build.gradle의 Flyway 주석이 적은 것과 같은 재편이다).
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actuator가 <b>열려야 할 만큼만 열려 있는지</b> 검증한다(백로그 P2-11-2).
 *
 * <h3>왜 실제 컨텍스트로 보는가</h3>
 * 여기서 검증하는 것은 자바 코드가 아니라 <b>설정 두 개의 상호작용</b>이다 --
 * {@code management.endpoints.web.exposure.include}가 무엇을 내보내는지와
 * {@code SecurityConfig}의 {@code requestMatchers} 순서가 누구를 통과시키는지.
 * 둘 다 단위 테스트로는 볼 수 없고, <b>한쪽만 고치면 조용히 어긋난다.</b>
 *
 * <p>이 저장소가 반복해서 겪은 것이 정확히 그것이다 -- 설정은 있는데 안 먹거나,
 * 두 설정이 서로 안 맞아 우연히 앞에 선 쪽이 이긴다. 그래서 컨테이너를 띄워서 본다.
 *
 * <h3>지키려는 경계</h3>
 * <ul>
 *   <li>{@code /actuator/health}는 <b>익명 허용</b> -- 로드밸런서와 compose 헬스체크가 닿아야 한다</li>
 *   <li>단, 익명에게는 <b>UP/DOWN만</b> -- 세부 항목에는 어떤 인프라를 쓰는지가 드러난다</li>
 *   <li>{@code /actuator/metrics}는 <b>ADMIN만</b> -- 힙·GC·커넥션 풀 수치를 밖에서 읽게 두지 않는다</li>
 *   <li>노출 목록에 없는 것은 <b>존재하지 않아야</b> 한다 -- {@code env}는 설정값 전체를 뱉는다</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Actuator - 열려야 할 만큼만 열려 있다")
class ActuatorEndpointTest extends ContainerTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("health는 토큰 없이 열린다 - 로드밸런서와 compose 헬스체크가 닿아야 한다")
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("익명에게는 세부 항목이 안 나간다 - 어떤 인프라를 쓰는지가 드러난다")
    void healthHidesComponentsFromAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                // components에는 db / redis / diskSpace가 이름 그대로 들어 있다.
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("metrics는 익명에게 닫혀 있다 - 힙·GC·풀 수치를 밖에서 읽게 두지 않는다")
    void metricsIsClosedToAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("일반 사용자도 metrics를 못 본다 - 로그인했다는 것과 서버 내부를 봐도 된다는 것은 다르다")
    void metricsIsClosedToOrdinaryUsers() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN은 metrics를 본다 - P2-9-1이 막으려던 풀 고갈을 확인할 수단이다")
    void adminCanReadMetrics() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk())
                // 이 이름이 나오는지가 곧 "풀 상태를 물어볼 수 있다"는 뜻이다.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("hikaricp.connections")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("info가 빌드 정보를 답한다 - '지금 도는 코드가 무엇인가'에 답이 없었다(P2-13-1)")
    void infoExposesBuildInformation() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.version").exists())
                .andExpect(jsonPath("$.build.commit").exists());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("노출 목록에 없는 env는 ADMIN에게도 없다 - 설정값 전체를 뱉는 엔드포인트다")
    void unexposedEndpointsDoNotExist() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isNotFound());
    }
}
