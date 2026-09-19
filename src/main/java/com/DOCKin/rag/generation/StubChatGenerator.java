package com.DOCKin.rag.generation;

import com.DOCKin.ai.dto.ChatDomain;
import com.DOCKin.rag.dto.RetrievalResult;
import com.DOCKin.rag.dto.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 모델 없는 생성기. {@code ai.chatbot.stub=true}일 때만 뜬다.
 *
 * <p>답변을 지어내지 않는다. 근거 목록을 번호·출처·유사도와 함께 그대로 돌려주고, 첫 줄에
 * 스텁임을 밝힌다. 그래서 이 응답을 보면 "무엇을 찾았는가"와 "무엇을 못 봤는가"(권한 밖)가
 * 그대로 드러난다 -- 시연이 보여줘야 하는 것은 답변 문장이 아니라 그 둘이다.
 *
 * <p>운영에서 켜지면 안 된다. 기동 로그에 WARN을 남긴다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.chatbot.stub", havingValue = "true")
public class StubChatGenerator implements ChatGenerator {

    static final String HEADER = "[스텁 응답 - 생성 모델 없이 검색 근거만 돌려줍니다]";
    private static final int SNIPPET = 80;

    public StubChatGenerator() {
        log.warn("[RAG] ai.chatbot.stub=true - 챗봇이 FastAPI 대신 스텁 생성기로 동작합니다. 운영에서는 끄십시오.");
    }

    @Override
    public ChatDomain.Response.Result generate(ChatDomain.Request augmented, RetrievalResult retrieval, String userId) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        if (retrieval.isEmpty()) {
            sb.append("근거를 찾지 못했습니다(retrieval_mode=").append(retrieval.mode())
              .append("). 권한 안에 해당 문서가 없거나 검색이 실패한 경우입니다.");
            return new ChatDomain.Response.Result(sb.toString());
        }
        sb.append("근거 ").append(retrieval.chunks().size()).append("건 (retrieval_mode=")
          .append(retrieval.mode()).append(")\n");
        int i = 1;
        for (RetrievedChunk c : retrieval.chunks()) {
            sb.append(i++).append(". ").append(c.sourceType()).append(" #").append(c.sourceId())
              .append(" (score ").append(String.format("%.3f", c.score())).append(") ")
              .append(snippet(c.content())).append('\n');
        }
        return new ChatDomain.Response.Result(sb.toString().stripTrailing());
    }

    static String snippet(String content) {
        if (content == null) return "";
        String oneLine = content.replace('\n', ' ').strip();
        return oneLine.length() <= SNIPPET ? oneLine : oneLine.substring(0, SNIPPET) + "…";
    }
}
