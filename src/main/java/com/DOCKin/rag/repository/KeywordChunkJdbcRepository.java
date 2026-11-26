package com.DOCKin.rag.repository;

import com.DOCKin.rag.model.SourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 임베딩 서버 장애 시 쓰는 키워드 폴백 검색.
 *
 * <p><b>맞은 키워드 수로 순위를 매긴다.</b> 이전에는 키워드마다 {@code LIKE}를 따로 걸고
 * {@code chunk_id DESC}(최신순)로 잘랐다. 그러면 "용접기"처럼 흔한 단어 하나가 최신 청크로
 * 후보를 다 채우고 부품·증상 단어는 쓰이지 않는다. 일지 2만 건에서 한국어 용어형 질문의
 * Hit@5가 0이었다(#186, AWS 밤 21). 여기서는 키워드 전부를 한 쿼리에 넣고,
 * 하나라도 맞은 청크를 <b>맞은 개수 내림차순</b>으로 정렬한다. 같으면 최신순이다.
 *
 * <p><b>권한 필터는 SQL 안에서 건다.</b> 벡터 검색과 같은 선필터를 공유해야 장애 상황에서만
 * 남의 일지가 근거로 새어 나가는 사고를 막는다. 키워드 경로는 어차피 전체를 훑으므로
 * 벡터 경로처럼 PUBLIC/OWNER를 쿼리 둘로 나눌 이유가 없다.
 *
 * <p>양쪽 와일드카드 {@code LIKE}라 인덱스를 타지 못한다(ADR-0002 2-2). 장애 때만 도는 경로다.
 * 키워드는 호출자가 글자·숫자만 남겨서 넘기므로 {@code %}·{@code _} 이스케이프가 필요 없다.
 */
@Repository
@RequiredArgsConstructor
public class KeywordChunkJdbcRepository {

    private static final RowMapper<KeywordChunk> ROW = (rs, i) -> new KeywordChunk(
            rs.getLong("chunk_id"),
            SourceType.valueOf(rs.getString("source_type")),
            rs.getLong("source_id"),
            rs.getInt("chunk_index"),
            rs.getString("content"),
            rs.getInt("matched"));

    private final JdbcClient jdbcClient;

    /**
     * 공개 청크, 본인 청크, 같은 구역의 승인분({@code AREA})에서 찾는다 — 벡터 경로와 같은 범위다(ADR-0012).
     * {@code area}가 null이면(구역 모름) 구역 승인분은 하나도 고르지 않는다.
     */
    public List<KeywordChunk> findVisible(String embeddingModel, String userId, String area,
                                          List<String> keywords, int limit) {
        return find(embeddingModel, userId, area, keywords, limit);
    }

    /** 관리자용. 권한 필터 없이 전체에서 찾는다. */
    public List<KeywordChunk> findAll(String embeddingModel, List<String> keywords, int limit) {
        return find(embeddingModel, null, null, keywords, limit);
    }

    private List<KeywordChunk> find(String embeddingModel, String userId, String area,
                                    List<String> keywords, int limit) {
        if (keywords.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < keywords.size(); i++) {
            hits.add("CASE WHEN content LIKE :kw" + i + " THEN 1 ELSE 0 END");
        }
        String matched = String.join(" + ", hits);

        StringBuilder sql = new StringBuilder()
                .append("SELECT chunk_id, source_type, source_id, chunk_index, content, matched\n")
                .append("FROM (\n")
                .append("  SELECT chunk_id, source_type, source_id, chunk_index, content, ")
                .append(matched).append(" AS matched\n")
                .append("  FROM document_chunks\n")
                .append("  WHERE embedding_model = :model\n");
        if (userId != null) {
            sql.append("    AND (visibility = 'PUBLIC' OR owner_user_id = :userId\n")
                    .append("         OR (visibility = 'AREA' AND area = :area))\n");
        }
        sql.append(") c\n")
                .append("WHERE matched > 0\n")
                .append("ORDER BY matched DESC, chunk_id DESC\n")
                .append("LIMIT :limit");

        JdbcClient.StatementSpec spec = jdbcClient.sql(sql.toString())
                .param("model", embeddingModel)
                .param("limit", limit);
        if (userId != null) {
            spec = spec.param("userId", userId).param("area", area);
        }
        for (int i = 0; i < keywords.size(); i++) {
            spec = spec.param("kw" + i, "%" + keywords.get(i) + "%");
        }
        return spec.query(ROW).list();
    }
}
