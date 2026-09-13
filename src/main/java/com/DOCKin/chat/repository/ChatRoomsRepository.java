package com.DOCKin.chat.repository;

import com.DOCKin.chat.model.ChatRooms;
import com.DOCKin.member.model.Member;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomsRepository extends JpaRepository<ChatRooms,Integer> {
    // last_message_* 갱신은 ChatJdbcRepository에 있다. 엔티티를 거치지 않는 UPDATE라 여기 두지 않는다.

    @Query("SELECT r FROM ChatRooms r JOIN r.members m WHERE m.member = :member")
    Page<ChatRooms> findByMembers(@Param("member") Member member, Pageable pageable);

}