package com.DOCKin.member.repository;

import com.DOCKin.member.model.Member;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MemberRepository extends JpaRepository<Member,String> {
    Optional<Member> findByUserId(String userId);
    List<Member> findByShipYardArea(String shipYardArea);

    /**
     * 잔여 연차를 갱신하기 위해 사용자 행에 비관적 쓰기 락({@code SELECT ... FOR UPDATE})을 건다.
     *
     * <h3>왜 필요한가</h3>
     * 휴가 승인은 <b>읽고-검사하고-쓰는</b> 순서로 잔여 연차를 다룬다. 락이 없으면
     * 관리자 두 명이 같은 사용자의 신청 두 건을 동시에 승인할 때 <b>둘 다 잔액 검사를 통과하고
     * 둘 다 차감</b>해서 잔액이 음수가 될 수 있다(lost update).
     *
     * <h3>왜 유니크 제약으로는 못 막나</h3>
     * ADR-0001의 출퇴근 중복은 "같은 행이 두 번 생기는" 문제라 유니크 제약이 최종 방어선이 됐다.
     * 잔여 연차는 <b>수치를 갱신</b>하는 문제라 제약으로 표현할 수 없다. 락이 유일한 수단이다.
     *
     * <h3>왜 분산락이 아니라 DB 락인가</h3>
     * 출퇴근은 <b>피크에 몰리는</b> 요청이라 DB 커넥션을 오래 잡지 않으려고 Redis 분산락을 앞단에 뒀다.
     * 휴가 승인은 관리자가 간헐적으로 하는 작업이라 경합이 드물고, 단일 DB이므로
     * {@code SELECT ... FOR UPDATE} 하나로 충분하다. 저장소를 하나 더 끌어들일 이유가 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.userId = :userId")
    Optional<Member> findByUserIdForUpdate(@Param("userId") String userId);
}
