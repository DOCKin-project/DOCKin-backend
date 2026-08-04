package com.DOCKin.rag.chunking;

import com.DOCKin.rag.model.SourceType;

import java.util.List;

/**
 * 원본 문서를 검색 단위(청크)로 자르는 전략.
 *
 * <p>소스 종류마다 적절한 절단 기준이 다르기 때문에 인터페이스로 분리했다.
 * 작업일지는 짧고 구어체라 고정 크기로 충분하지만, 사내 규정 문서(Phase 1.5)는
 * 조/항 구조가 있어 조항 경계를 무시하고 자르면 "제3조 2항"의 앞뒤가 끊겨
 * 검색은 되더라도 인용이 무의미해진다.
 *
 * <p>구현체는 스프링 빈으로 등록되며 {@link #supports(SourceType)}로 선택된다.
 * 새 소스를 추가할 때 기존 구현을 건드리지 않고 전략만 추가하면 된다.
 */
public interface ChunkingStrategy {

    /** 이 전략이 처리할 수 있는 소스 종류인지 */
    boolean supports(SourceType sourceType);

    /**
     * 텍스트를 청크 목록으로 자른다. 반환 순서가 곧 {@code chunk_index}가 된다.
     *
     * @return 빈 입력이면 빈 리스트. 자를 필요가 없으면 원문 1건짜리 리스트
     */
    List<String> split(String text);
}
