package com.DOCKin.safetyCourse.repository;

import com.DOCKin.safetyCourse.model.SafetyCourse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;


public interface SafetyCourseRepository extends JpaRepository<SafetyCourse, Integer> {

    @Query("SELECT s FROM SafetyCourse s WHERE "
    +"LOWER(s.title) LIKE LOWER(CONCAT('%',:keyword,'%')) OR "+
    "LOWER(s.description) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<SafetyCourse> searchByKeyword(String keyword, Pageable pageable);

    Page<SafetyCourse> findByCreatedBy(String createdBy, Pageable pageable);

    /**
     * RAG 인덱싱 배치 전용 커서(keyset) 조회.
     *
     * <p>{@code LIMIT/OFFSET}은 앞의 행을 읽고 버려 뒤 페이지로 갈수록 느려지고,
     * 순회 중 INSERT가 일어나면 페이지가 밀려 행이 누락된다.
     * PK로 시작점을 찾는 커서 방식은 페이지 위치와 무관하게 비용이 일정하다.
     */
    @Query("""
            SELECT s FROM SafetyCourse s
            WHERE s.courseId > :lastId
            ORDER BY s.courseId ASC
            """)
    List<SafetyCourse> findForIndexingAfter(@Param("lastId") Integer lastId, Pageable pageable);
}
