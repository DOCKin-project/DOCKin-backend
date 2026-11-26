package com.DOCKin.rag.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEI {@code /rerank} 계약 — 요청 모양, 점수를 입력 순서로 되돌리기, 실패 시 빈 값.
 * JDK 내장 HTTP 서버로 TEI 응답을 흉내 낸다(외부 의존 없음).
 */
class RerankClientTest {

    private HttpServer server;

    private RerankClient clientReturning(int status, String body, AtomicReference<String> request) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        WebClient web = WebClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build();
        return new RerankClient(web, 2000);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("TEI는 점수 내림차순으로 준다 - 입력 순서로 되돌린다, 요청에 query·texts·raw_scores=false가 간다")
    void scoresBackInInputOrder() throws IOException {
        AtomicReference<String> request = new AtomicReference<>();
        RerankClient client = clientReturning(200,
                "[{\"index\":1,\"score\":0.9},{\"index\":2,\"score\":0.5},{\"index\":0,\"score\":0.1}]", request);

        Optional<double[]> scores = client.score("롤러 진동", List.of("a", "b", "c"));

        assertThat(scores).isPresent();
        assertThat(scores.get()).containsExactly(0.1, 0.9, 0.5);
        assertThat(request.get()).contains("\"query\":\"롤러 진동\"")
                .contains("\"texts\":[\"a\",\"b\",\"c\"]")
                .contains("\"raw_scores\":false")
                .contains("\"truncate\":true");
    }

    @Test
    @DisplayName("서버 오류·개수 불일치는 예외 대신 빈 값 - 호출자는 재순위 없이 진행한다")
    void failuresAreEmpty() throws IOException {
        AtomicReference<String> request = new AtomicReference<>();
        assertThat(clientReturning(500, "{\"error\":\"boom\"}", request).score("q", List.of("a"))).isEmpty();
        stop();
        assertThat(clientReturning(200, "[{\"index\":0,\"score\":0.3}]", request).score("q", List.of("a", "b")))
                .isEmpty();
    }

    @Test
    @DisplayName("서버가 없으면 빈 값")
    void unreachableIsEmpty() {
        RerankClient client = new RerankClient(WebClient.builder().baseUrl("http://127.0.0.1:1").build(), 500);
        assertThat(client.score("q", List.of("a"))).isEmpty();
    }
}
