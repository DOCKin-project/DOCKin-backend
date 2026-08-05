package com.DOCKin.rag.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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
     * 임베딩 차원. 현재 모델 {@code intfloat/multilingual-e5-small}의 출력 차원이다.
     *
     * <p><b>컬럼 정의에 박히는 값이라 모델과 함께 바뀐다.</b> {@code @Array(length)}는 애노테이션이라
     * 컴파일 상수여야 하므로 설정으로 뺄 수 없다 — 차원은 런타임에 고를 수 있는 값이 아니라
     * 스키마의 일부라는 뜻이기도 하다. 다른 차원의 모델로 교체하려면 컬럼 마이그레이션이 따라온다.
     */
    public static final int EMBEDDING_DIM = 384;

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
     * 임베딩 벡터. pgvector의 {@code vector(384)} 컬럼에 매핑된다.
     *
     * <h4>커스텀 UserType이 필요 없다</h4>
     * Hibernate 6.4부터 {@code hibernate-vector} 모듈이 {@link SqlTypes#VECTOR}를 제공하고,
     * PostgreSQL 방언에서 이를 pgvector의 {@code vector} 타입으로 내보낸다.
     * {@code @Array(length)}가 곧 컬럼의 차원 선언이다.
     *
     * <h4>왜 BYTEA가 아닌가</h4>
     * MySQL 시절 {@code VARBINARY(4096)}, 2a에서 {@code BYTEA}였다. 둘 다 DB가 보기에는
     * 그냥 바이트 뭉치라 <b>유사도 계산을 애플리케이션에서 할 수밖에 없었다</b> — 검색 한 번에
     * 후보 벡터 전체(10만 청크 기준 146MB)를 JVM으로 끌어와야 했고, 실측상 그 전송이 병목의 81%였다.
     * {@code vector}는 DB가 의미를 아는 타입이라 거리 연산자({@code <=>})와 HNSW 인덱스가 붙는다.
     *
     * <h4>차원을 384로 고정한 대가</h4>
     * <b>HNSW 인덱스는 차원이 고정된 컬럼에만 만들 수 있다.</b> pgvector의 {@code vector}는
     * 차원 없이 선언할 수도 있지만 그러면 인덱스를 걸지 못해 ANN 도입 자체가 무의미해진다.
     * 그 대가로 <b>차원이 다른 모델의 청크가 한 컬럼에 공존할 수 없게 됐다</b>
     * — 가변 길이였던 BYTEA 시절에는 가능했던 일이다.
     * 차원이 다른 모델로 교체하려면 새 컬럼(또는 새 테이블)을 만들어 옮겨야 한다.
     */
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = EMBEDDING_DIM)
    @Column(name = "embedding", nullable = false)
    private float[] embedding;

    /**
     * 이 벡터의 차원 수.
     *
     * <p>BYTEA 시절에는 바이트 배열에서 벡터를 복원하는 데 반드시 필요했고, 차원이 다른 청크가
     * 공존할 수 있어 비교 가능 여부를 가리는 근거이기도 했다. {@code vector(384)}로 바뀌면서
     * <b>DB 제약이 같은 역할을 하게 되어 항상 {@link #EMBEDDING_DIM}과 같은 값이 된다.</b>
     *
     * <p>그럼에도 남겨두는 이유는 {@link #embeddingModel}과 짝을 이뤄
     * "이 행이 몇 차원 모델로 만들어졌는가"를 데이터 자체에 남기기 위함이다.
     * 컬럼 차원을 넓히는 이행기에 구/신 청크를 구분하는 근거가 된다.
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
                         float[] embedding, Integer embeddingDim, String embeddingModel,
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
    public void reindex(String content, String contentHash, float[] embedding, Integer embeddingDim) {
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
