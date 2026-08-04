package com.DOCKin.rag.model;

/**
 * 청크의 원본 종류.
 * document_chunks는 여러 원본 테이블을 (source_type, source_id)로 다형 참조하므로 FK를 걸 수 없다.
 */
public enum SourceType {
    /** work_logs.log_text — 작성자 본인/ADMIN만 조회 가능 */
    WORK_LOG,
    /** work_log_translations.translated_text — 교차언어 검색 대상. 원본 작업일지의 권한을 따른다 */
    WORK_LOG_TRANSLATION,
    /** safety_courses.description — 전체 공개 */
    SAFETY_COURSE,
    /** checklist_items — 전체 공개 */
    CHECKLIST_ITEM
}
