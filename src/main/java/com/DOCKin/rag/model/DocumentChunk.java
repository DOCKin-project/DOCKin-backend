package com.DOCKin.rag.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * RAG 검색 대상 문서 청크.
 *
 * <p>설계 근거는 {@code docs/SERVICE-SCALE-ASSUMPTIONS.md} 3-2절 참고.
 * Phase 1은 ANN 인덱스 없이 전체 행을 훑는 브루트포스(정확 최근접)로 검색한다.
 * 챗봇 트래픽이 약 0.02 TPS로 지연시간 요구가 사실상 없고, recall 손실이 0이라
 * Phase 2에서 ANN을 도입할 때 이 결과가 정답(ground truth) 기준선이 되기 때문이다.
 *
 * <p>브루트포스 상한은 {@code Xmx400M} 기준 약 10만 청크다(청크당 1.5KB).
 * 이를 넘어서면 Phase 2(pgvector HNSW)로 전환한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "document_chunks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_chunk_source",
                // 재색인 시 UPSERT 기준 키. 모델을 포함해야 모델 교체분과 기존분이 공존할 수 있다.
                columnNames = {"source_type", "source_id", "chunk_index", "embedding_model"}
        ),
        indexes = {
                @Index(name = "idx_chunk_source", columnList = "source_type, source_id"),
                @Index(name = "idx_chunk_model", columnList = "embedding_model"),
                @Index(name = "idx_chunk_visibility", columnList = "visibility, owner_user_id")
        }
)
public class DocumentChunk {

    /**
     * PK.
     *
     * <p><b>{@code IDENTITY}가 아니라 {@code SEQUENCE}인 이유:</b> IDENTITY는 INSERT 직후
     * 생성된 키를 읽어야 해서 Hibernate가 JDBC 배치를 포기한다. 인덱싱이 10만 건 단위 적재라
     * 배치가 막히면 왕복이 10만 번 발생한다(MySQL 시절 {@code Com_insert}로 확인한 문제).
     * 시퀀스는 INSERT <b>전에</b> 키를 받아오므로 문장을 묶을 수 있다.
     *
     * <p>{@code allocationSize}를 배치 크기에 맞춰 50으로 둔다. 기본값 50이지만 명시해
     * "왜 시퀀스를 한 번 호출하고 50개를 쓰는가"를 드러낸다 — 매 INSERT마다 시퀀스를 호출하면
     * 배치로 묶어도 왕복이 그만큼 생긴다.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "document_chunk_seq")
    @SequenceGenerator(name = "document_chunk_seq", sequenceName = "document_chunk_seq", allocationSize = 50)
    @Column(name = "chunk_id")
    private Long chunkId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private SourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    /** 한 문서를 여러 청크로 나눴을 때의 순번 (0부터) */
    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "language_code", length = 10)
    private String languageCode;

    /** 청크 원문. 검색에 걸리면 이 값이 챗봇 프롬프트에 근거로 주입된다. */
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * 원문의 SHA-256(hex). 멱등 재색인용.
     *
     * <p>10만 청크 인덱싱에 약 20분이 걸리므로(배치 12ms/건 실측) 배치가 중간에 끊길 수 있다.
     * 재실행 시 해시가 같으면 임베딩 호출을 건너뛰어 이어서 돌린다.
     */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /**
     * float32 배열의 리틀엔디언 바이트 표현. 현재 모델은 384차원 = 1536 bytes.
     *
     * <p>BLOB이 아니라 VARBINARY로 둔 이유: 브루트포스가 전체 행을 읽으므로
     * 오프페이지 저장을 피하고 인라인으로 읽는 편이 유리하다.
     *
     * <p>MySQL 시절에는 {@code VARBINARY(4096)}이었다. 브루트포스가 전체 행을 훑으므로
     * BLOB의 오프페이지 저장을 피하려는 선택이었고, 상한 4096은 1024차원까지 수용하기 위함이었다.
     * PostgreSQL에는 그 구분이 없어 {@code BYTEA} 하나로 대체된다(길이 제한도 불필요).
     *
     * <p><b>2b에서 pgvector의 {@code vector} 타입으로 바뀔 자리다.</b> 지금은 이관 자체를
     * 검증하는 단계라 바이트 표현을 유지한다 — 유사도 계산이 여전히 애플리케이션에서 일어난다.
     * {@link #embeddingDim}과 함께 읽어야 벡터를 복원할 수 있다.
     */
    @Column(name = "embedding", nullable = false, columnDefinition = "BYTEA")
    private byte[] embedding;

    /**
     * 이 벡터의 차원 수. {@code embedding.length == embeddingDim * 4}가 성립해야 한다.
     *
     * <p>모델 교체 과도기에는 차원이 다른 청크가 한 테이블에 공존할 수 있으므로,
     * 코사인 유사도 계산 전에 차원이 일치하는지 확인하는 근거가 된다.
     */
    @Column(name = "embedding_dim", nullable = false)
    private Integer embeddingDim;

    /** 예: {@code intfloat/multilingual-e5-small}. 모델 교체 시 구버전 청크를 식별·삭제하는 데 쓴다. */
    @Column(name = "embedding_model", nullable = false, length = 64)
    private String embeddingModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 16)
    private Visibility visibility;

    /**
     * 권한 필터용 비정규화 컬럼. {@link Visibility#OWNER}일 때만 채운다.
     *
     * <p>브루트포스는 전체 스캔이라 검색마다 원본 테이블을 조인하면 비용이 커진다.
     * 소유자를 청크에 복제해두고 인덱스로 선필터한다.
     */
    @Column(name = "owner_user_id", length = 50)
    private String ownerUserId;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Builder
    public DocumentChunk(SourceType sourceType, Long sourceId, Integer chunkIndex,
                         String languageCode, String content, String contentHash,
                         byte[] embedding, Integer embeddingDim, String embeddingModel,
                         Visibility visibility, String ownerUserId) {
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.chunkIndex = chunkIndex == null ? 0 : chunkIndex;
        this.languageCode = languageCode;
        this.content = content;
        this.contentHash = contentHash;
        this.embedding = embedding;
        this.embeddingDim = embeddingDim;
        this.embeddingModel = embeddingModel;
        this.visibility = visibility == null ? Visibility.PUBLIC : visibility;
        this.ownerUserId = ownerUserId;
    }

    /**
     * 원본이 수정되어 내용이 바뀐 경우 청크를 갱신한다.
     * 해시가 같으면 인덱싱 배치가 이 메서드를 호출하지 않고 건너뛴다.
     */
    public void reindex(String content, String contentHash, byte[] embedding, Integer embeddingDim) {
        this.content = content;
        this.contentHash = contentHash;
        this.embedding = embedding;
        this.embeddingDim = embeddingDim;
    }

    /** 원본 권한 정보가 바뀐 경우(예: 작성자 변경) 비정규화 컬럼을 맞춘다. */
    public void updateVisibility(Visibility visibility, String ownerUserId) {
        this.visibility = visibility;
        this.ownerUserId = ownerUserId;
    }
}
