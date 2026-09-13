package com.DOCKin.chat.repository;

import com.DOCKin.chat.model.ChatMembers;
import com.DOCKin.chat.model.ChatRooms;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;


import java.util.List;
import java.util.Optional;

public interface ChatMembersRepository extends JpaRepository<ChatMembers,Integer> {
boolean existsByChatRoomsAndMemberUserId(ChatRooms chatRooms, String userId);

@Modifying
    @Transactional
    void deleteByChatRoomsAndMemberUserId(ChatRooms chatRooms,String userId);

    long countByChatRooms(ChatRooms rooms);

    Optional<ChatMembers> findByChatRoomsRoomIdAndMemberUserId(Integer roomId, String userId);

    boolean existsByChatRoomsRoomIdAndMemberUserId(Integer roomId, String userId);

    List<ChatMembers> findByChatRoomsRoomId(Integer roomId);

    // last_read_time 갱신은 ChatJdbcRepository에 있다. 엔티티를 거치지 않는 UPDATE라 여기 두지 않는다.
}
