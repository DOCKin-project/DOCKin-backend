package com.DOCKin.global.config;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * prod 프로파일로 <b>실제로 뜨는지</b>, 그리고 뜬 뒤에 운영에서 달라야 할 것이 달라졌는지.
 *
 * <p>프로파일 파일은 운영 서버에서만 켜진다. 여기서 안 보면 첫 배포가 곧 첫 기동 시험이 된다 —
 * 그때 실패하면 배포 스크립트가 이전 이미지로 돌리긴 하지만(scripts/deploy/deploy.sh), 이유는 서버 로그에만 남는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@TestPropertySource(properties = "CORS_ALLOWED_ORIGINS=https://app.example.test")
@DisplayName("prod 프로파일 - 뜨고, Swagger가 닫히고, 종료가 graceful이다")
class ProdProfileTest extends ContainerTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Value("${server.shutdown}")
    private String shutdown;

    @Value("${security.cors.allowed-origins}")
    private String[] corsOrigins;

    @Test
    @DisplayName("API 문서가 404다 - 화이트리스트에 있어도 엔드포인트가 없다")
    void apiDocsAreGone() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("health는 그대로 열려 있다 - 배포 스크립트와 compose 헬스체크가 본다")
    void healthStillPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("CORS는 환경변수 값만 쓴다 - localhost 기본값이 섞이지 않는다")
    void corsComesOnlyFromEnv() {
        assertThat(corsOrigins).containsExactly("https://app.example.test");
    }

    @Test
    @DisplayName("종료가 graceful이다 - 배포 때마다 처리 중인 요청이 끊기지 않는다")
    void gracefulShutdown() {
        assertThat(shutdown).isEqualTo("graceful");
    }
}
