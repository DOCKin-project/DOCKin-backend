package com.DOCKin.global.config;

import org.apache.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {
    @Value("${external-api.fastapi.base-url}")
    private String fastapiBaseUrl;

    @Value("${external-api.embedding.base-url}")
    private String embeddingBaseUrl;

    @Bean
    public WebClient fastApiWebClient(){
        return WebClient.builder()
                .baseUrl(fastapiBaseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * RAG 임베딩 추론 서버(TEI) 전용 클라이언트.
     *
     * <p>팀원이 담당하는 FastAPI(번역/STT/챗봇)와는 별개 서비스이므로 클라이언트를 분리한다.
     * 배치 임베딩 응답이 클 수 있어(32건 x 384차원) 기본 256KB 버퍼로는 부족하므로 상한을 올린다.
     */
    @Bean
    public WebClient embeddingWebClient(){
        return WebClient.builder()
                .baseUrl(embeddingBaseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
    }

}

