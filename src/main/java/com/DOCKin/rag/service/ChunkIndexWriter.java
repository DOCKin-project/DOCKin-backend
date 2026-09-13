package com.DOCKin.rag.service;

import com.DOCKin.rag.chunking.ChunkingStrategy;
import com.DOCKin.rag.model.DocumentChunk;
import com.DOCKin.rag.model.SourceType;
import com.DOCKin.rag.model.Visibility;
import com.DOCKin.rag.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 페이지 단위로 청크를 적재하는 트랜잭션 경계.
 *
 * <p><b>{@link IndexingService}와 분리한 이유는 자기호출(self-invocation) 때문이다.</b>
 * 스프링의 {@code @Transactional}은 프록시 기반이라 같은 클래스 안에서 호출하면 프록시를 거치지 않아
 * 트랜잭션이 걸리지 않는다. 페이지 단위 커밋이 재시작 가능성의 핵심이므로 별도 빈으로 뺐다.
 *
 * <p>임베딩 HTTP 호출이 트랜잭션 안에서 일어난다. 페이지 100건 기준 수 초 수준이며,
 * 새벽 배치라 커넥션 점유 시간이 문제되지 않는다고 판단했다. 온라인 경로에서 재사용할 경우
 * 준비/임베딩/저장 3단계로 쪼개야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkIndexWriter {

    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingClient embeddingClient;
    private final List<ChunkingStrategy> chunkingStrategies;

    /** 색인 대상 원본 하나의 스냅샷. 원본 엔티티 종류에 의존하지 않도록 평평하게 만든다. */
    public record IndexTarget(SourceType sourceType, Long sourceId, String text,
                              String languageCode, Visibility visibility, String ownerUserId) {}

    /**
     * 원본 목록을 청킹·임베딩해 저장한다. 한 번의 호출이 하나의 트랜잭션이다.
     *
     * @return 이번 호출에서 실제로 임베딩한 청크 수 (해시가 같아 건너뛴 것은 제외)
     */
    @Transactional
    public int writePage(List<IndexTarget> targets) {
        List<PendingChunk> pending = new ArrayList<>();
        for (IndexTarget target : targets) {
            collectPending(pending, target);
        }
        return flush(pending);
    }

    /**
     * 문서 하나를 청킹해 변경분만 모은다.
     * 해시가 같은 청크는 임베딩 호출 없이 건너뛴다 — 멱등성과 재시작의 핵심이다.
     */
    private void collectPending(List<PendingChunk> pending, IndexTarget target) {
        String model = embeddingClient.getModelName();
        List<String> chunks = resolveStrategy(target.sourceType()).split(target.text());

        Map<Integer, DocumentChunk> existing = new HashMap<>();
        for (DocumentChunk c : documentChunkRepository.findBySourceTypeAndSourceIdAndEmbeddingModel(
                target.sourceType(), target.sourceId(), model)) {
            existing.put(c.getChunkIndex(), c);
        }

        for (int i = 0; i < chunks.size(); i++) {
            String content = chunks.get(i);
            String hash = sha256(content);
            DocumentChunk prev = existing.remove(i);

            if (prev != null && hash.equals(prev.getContentHash())) {
                // 내용은 그대로다. 원본 권한이 바뀌었을 수 있으므로 그것만 맞춘다.
                prev.updateVisibility(target.visibility(), target.ownerUserId());
                continue;
            }
            pending.add(new PendingChunk(target, i, content, hash, prev));
        }

        // 원본이 짧아져 청크 수가 줄어든 경우 남은 구버전 청크를 제거한다.
        existing.values().forEach(documentChunkRepository::delete);
    }

    /** 모아둔 변경분을 배치로 임베딩해 저장한다. 배치가 단건 대비 약 8배 빠르다(실측). */
    private int flush(List<PendingChunk> pending) {
        if (pending.isEmpty()) {
            return 0;
        }
        List<String> texts = pending.stream().map(PendingChunk::content).toList();
        List<float[]> vectors = embeddingClient.embedPassages(texts);
        String model = embeddingClient.getModelName();

        List<DocumentChunk> toSave = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            PendingChunk p = pending.get(i);
            IndexTarget t = p.target();
            // 임베딩 서버가 준 float[]를 그대로 넘긴다. pgvector 매핑 전에는 여기서
            // 리틀엔디언 바이트로 눕히는 단계가 있었다(EmbeddingClient.toBytes).
            float[] vector = vectors.get(i);

            if (p.previous() != null) {
                p.previous().reindex(p.content(), p.contentHash(), vector, vector.length);
                p.previous().updateVisibility(t.visibility(), t.ownerUserId());
            } else {
                toSave.add(DocumentChunk.builder()
                        .sourceType(t.sourceType())
                        .sourceId(t.sourceId())
                        .chunkIndex(p.chunkIndex())
                        .languageCode(t.languageCode())
                        .content(p.content())
                        .contentHash(p.contentHash())
                        .embedding(vector)
                        .embeddingDim(vector.length)
                        .embeddingModel(model)
                        .visibility(t.visibility())
                        .ownerUserId(t.ownerUserId())
                        .build());
            }
        }
        documentChunkRepository.saveAll(toSave);
        return pending.size();
    }

    private ChunkingStrategy resolveStrategy(SourceType sourceType) {
        return chunkingStrategies.stream()
                .filter(s -> s.supports(sourceType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("청킹 전략이 없습니다: " + sourceType));
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    /** 임베딩 대기 중인 청크. {@code previous}가 있으면 갱신, 없으면 신규다. */
    private record PendingChunk(IndexTarget target, int chunkIndex,
                                String content, String contentHash,
                                DocumentChunk previous) {}
}
