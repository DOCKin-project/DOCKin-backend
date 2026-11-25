package com.DOCKin.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProdProfileGuard - 운영에 개발용 스위치가 섞이면 기동을 거부한다")
class ProdProfileGuardTest {

    @Test
    @DisplayName("prod만 켜져 있으면 통과한다")
    void prodAlonePasses() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("ai.chatbot.stub", "false");

        assertThat(ProdProfileGuard.check(env)).isEmpty();
    }

    @Test
    @DisplayName("seed가 같이 켜지면 거부한다 - 표본 데이터가 운영 DB에 들어간다")
    void seedWithProdIsRejected() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod", "seed");
        env.setProperty("spring.flyway.locations", "classpath:db/migration,classpath:db/seed");

        assertThatThrownBy(() -> new ProdProfileGuard(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed 프로파일")
                .hasMessageContaining("db/seed");
    }

    @Test
    @DisplayName("챗봇 스텁이 켜져 있으면 거부한다 - 에러가 아니라 근거 목록이 답으로 나간다")
    void chatbotStubIsRejected() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("ai.chatbot.stub", "true");

        assertThatThrownBy(() -> new ProdProfileGuard(env))
                .hasMessageContaining("ai.chatbot.stub");
    }
}
