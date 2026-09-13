package com.DOCKin.ai.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "chat_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "user_query", columnDefinition = "TEXT")
    private String userQuery;

    @Column(name = "reply", columnDefinition = "TEXT")
    private String reply;

    /**
     * 이 답변의 근거로 사용된 청크 ID 목록(쉼표 구분).
     *
     * <p>RAG 답변은 "왜 그렇게 답했는가"를 사후에 확인할 수 있어야 한다.
     * 잘못된 답변이 나왔을 때 모델이 문제인지 검색이 문제인지 가르는 유일한 단서이며,
     * 규정 근거를 다루는 도메인에서는 감사 추적 자체가 요구사항이 된다.
     * 근거 없이 답한 경우(검색 결과 없음 / 폴백 실패)에는 null이다.
     */
    @Column(name = "source_chunk_ids", columnDefinition = "TEXT")
    private String sourceChunkIds;

    /** 근거 검색 방식. VECTOR(정상) / KEYWORD(임베딩 서버 장애로 폴백) / NONE(근거 없음) */
    @Column(name = "retrieval_mode", length = 16)
    private String retrievalMode;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public ChatLog(String traceId, String userId, String userQuery, String reply,
                   String sourceChunkIds, String retrievalMode) {
        this.traceId = traceId;
        this.userId = userId;
        this.userQuery = userQuery;
        this.reply = reply;
        this.sourceChunkIds = sourceChunkIds;
        this.retrievalMode = retrievalMode;
        this.createdAt = LocalDateTime.now();
    }
}
