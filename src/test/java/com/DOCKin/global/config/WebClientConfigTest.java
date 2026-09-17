package com.DOCKin.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검증: FastAPI 클라이언트의 서비스 토큰 헤더 (P2-20-7).
 *
 * <p>네트워크 없이 본다 — {@code exchangeFunction}으로 나가는 요청을 가로채 헤더만 확인한다.
 * 검사 대상은 {@code WebClientConfig.withServiceToken} 하나: 값이 있으면 {@code X-Service-Token}으로
 * 나가고, 비어 있으면 헤더 자체가 없어야 한다(빈 값을 실으면 FastAPI가 "있는데 틀림"으로 401을 낸다).
 * 그리고 사용자의 {@code Authorization}은 어떤 경우에도 없다 — 그게 이 항목의 출발점이었다.
 */
@DisplayName("FastAPI 클라이언트 - 서비스 토큰은 X-Service-Token으로, 비면 안 싣는다")
class WebClientConfigTest {

    @Test
    @DisplayName("토큰이 있으면 모든 요청에 X-Service-Token이 실린다")
    void tokenPresent() {
        List<ClientRequest> sent = new ArrayList<>();
        WebClient client = WebClientConfig.withServiceToken(capturing(sent), "shared-secret").build();

        client.post().uri("/api/chatbot").retrieve().toBodilessEntity().block();

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).headers().getFirst(WebClientConfig.SERVICE_TOKEN_HEADER)).isEqualTo("shared-secret");
        assertThat(sent.get(0).headers().containsHeader("Authorization")).isFalse();
    }

    @Test
    @DisplayName("토큰이 비면 헤더가 아예 없다 - 빈 값을 실으면 FastAPI가 '있는데 틀림'으로 401을 낸다")
    void tokenBlank() {
        for (String blank : new String[] {null, "", "   "}) {
            List<ClientRequest> sent = new ArrayList<>();
            WebClient client = WebClientConfig.withServiceToken(capturing(sent), blank).build();

            client.post().uri("/api/chatbot").retrieve().toBodilessEntity().block();

            assertThat(sent.get(0).headers().containsHeader(WebClientConfig.SERVICE_TOKEN_HEADER))
                    .as("token=[%s]", blank).isFalse();
        }
    }

    private static WebClient.Builder capturing(List<ClientRequest> sink) {
        return WebClient.builder()
                .baseUrl("http://fastapi.test")
                .exchangeFunction(request -> {
                    sink.add(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                });
    }
}
