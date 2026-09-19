package com.DOCKin.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "챗봇 dto")
public class ChatDomain {
    @Schema(description = "챗봇 request dto")
    public record Request(
            @Schema(description = "메시지 항목")
            List<Message> messages,

            @Schema(description = "언어")
            String lang,

            @Schema(description = "추적 ID")
            String traceId
    ){
        @Schema(description = "개별 메시지 상세")
        public record Message(
                @Schema(description = "역할")
                String role,

                @Schema(description = "메시지 내용")
                String content){}
    }

    @Schema(description = "챗봇 response dto")
    public record Response(
            @Schema(description = "추적 ID")
            String traceId,

            @Schema(description = "결과 데이터")
            Result result,

            @Schema(description = "답변에 쓰인 근거. 권한 선필터를 거친 것만 들어 있다 (#95)")
            Retrieval retrieval
    ){
        /** FastAPI 응답을 그대로 감쌀 때. 근거는 RagChatService가 뒤에 붙인다. */
        public Response(String traceId, Result result) {
            this(traceId, result, null);
        }

        @Schema(description = "응답 결과 상세")
        public record Result(
                @Schema(description = "챗봇 답변 내용")
                String reply){}

        @Schema(description = "근거 검색 결과 요약")
        public record Retrieval(
                @Schema(description = "VECTOR(벡터)·KEYWORD(임베딩 장애 폴백)·NONE(근거 없음)")
                String mode,

                @Schema(description = "프롬프트에 들어간 근거, 유사도 내림차순")
                List<Source> sources){}

        @Schema(description = "근거 한 건")
        public record Source(
                Long chunkId,
                @Schema(description = "WORK_LOG·WORK_LOG_TRANSLATION·SAFETY_COURSE·CHECKLIST_ITEM")
                String sourceType,
                Long sourceId,
                @Schema(description = "코사인 유사도. 키워드 폴백이면 의미 없음")
                double score){}
    }
}
