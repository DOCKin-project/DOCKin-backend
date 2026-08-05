package com.DOCKin.global.config;

import io.netty.channel.ChannelOption;
import org.apache.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * 외부 HTTP 클라이언트.
 *
 * <h3>타임아웃이 하나도 없었다 (2026-08-05 추가)</h3>
 * Reactor Netty의 기본값은 <b>연결 타임아웃 30초, 응답 타임아웃 없음(무한)</b>이다.
 * 즉 <b>연결은 됐는데 응답이 오지 않는 상대</b>를 만나면 영원히 매달린다.
 *
 * <p>그리고 이 프로젝트에는 그것이 특히 나쁘게 겹치는 지점이 있다 —
 * {@link com.DOCKin.rag.service.ChunkIndexWriter#writePage}는
 * <b>{@code @Transactional} 안에서 임베딩 HTTP를 호출한다</b>(그 클래스 주석이 인정한 부채다).
 * 임베딩 서버가 멎으면 이렇게 번진다:
 *
 * <pre>
 *   임베딩 호출이 무한 대기
 *     → 트랜잭션이 닫히지 않는다
 *     → DB 커넥션이 반납되지 않는다
 *     → HikariCP 풀이 마른다
 *     → 배치가 아니라 서비스 전체가 멎는다
 * </pre>
 *
 * <b>단일 외부 의존의 장애가 전체로 번지는 경로</b>이고, 타임아웃이 그 고리를 끊는다.
 *
 * <h3>왜 두 클라이언트의 값이 다른가</h3>
 * <ul>
 *   <li><b>임베딩(TEI)</b> — 배치 32건이 실측 약 12ms/건이라 정상 응답은 1초 안쪽이다.
 *       넉넉히 잡아도 되지만 <b>트랜잭션을 물고 있으므로 짧을수록 좋다.</b></li>
 *   <li><b>FastAPI</b> — 번역·STT·챗봇이라 본래 오래 걸린다. 다만 무한정 늘릴 이유는 없다:
 *       Nginx {@code proxy_read_timeout} 기본값이 60초라
 *       <b>그보다 오래 기다려봐야 사용자는 이미 끊긴 뒤다</b>(백로그 P2-9-3).</li>
 * </ul>
 *
 * <p>두 값 모두 설정으로 뺐다. 감으로 정한 값이므로 실측 후 조정할 수 있어야 한다.
 *
 * <h3>연결 재사용은 걱정하지 않아도 된다</h3>
 * Reactor Netty는 기본으로 커넥션 풀을 쓰므로 keep-alive가 동작한다.
 * 문제는 재사용이 아니라 <b>포기할 시점이 없다는 것</b>이었다.
 */
@Configuration
public class WebClientConfig {

    @Value("${external-api.fastapi.base-url}")
    private String fastapiBaseUrl;

    @Value("${external-api.embedding.base-url}")
    private String embeddingBaseUrl;

    @Value("${external-api.fastapi.response-timeout-ms}")
    private long fastapiResponseTimeoutMs;

    @Value("${external-api.embedding.response-timeout-ms}")
    private long embeddingResponseTimeoutMs;

    @Value("${external-api.connect-timeout-ms}")
    private int connectTimeoutMs;

    @Bean
    public WebClient fastApiWebClient() {
        return WebClient.builder()
                .baseUrl(fastapiBaseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(connector(fastapiResponseTimeoutMs))
                .build();
    }

    /**
     * RAG 임베딩 추론 서버(TEI) 전용 클라이언트.
     *
     * <p>팀원이 담당하는 FastAPI(번역/STT/챗봇)와는 별개 서비스이므로 클라이언트를 분리한다.
     * 배치 임베딩 응답이 클 수 있어(32건 x 384차원) 기본 256KB 버퍼로는 부족하므로 상한을 올린다.
     */
    @Bean
    public WebClient embeddingWebClient() {
        return WebClient.builder()
                .baseUrl(embeddingBaseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .clientConnector(connector(embeddingResponseTimeoutMs))
                .build();
    }

    /**
     * 연결 타임아웃과 응답 타임아웃을 건 커넥터.
     *
     * <p>둘은 막는 것이 다르다. <b>연결 타임아웃</b>은 상대가 아예 없거나 TCP 수립이 안 되는 경우를,
     * <b>응답 타임아웃</b>은 연결은 됐는데 응답이 오지 않는 경우를 막는다.
     * 후자가 없으면 <b>가장 나쁜 장애 형태(멎었지만 살아 있는 척하는 상대)를 그대로 받는다.</b>
     */
    private ReactorClientHttpConnector connector(long responseTimeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(responseTimeoutMs));
        return new ReactorClientHttpConnector(httpClient);
    }
}
