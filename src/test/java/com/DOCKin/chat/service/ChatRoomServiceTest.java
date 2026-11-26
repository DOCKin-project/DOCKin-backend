package com.DOCKin.chat.service;

import com.DOCKin.chat.dto.ChatRoomUpdateRequestDto;
import com.DOCKin.chat.model.ChatMembers;
import com.DOCKin.chat.model.ChatRooms;
import com.DOCKin.chat.repository.ChatJdbcRepository;
import com.DOCKin.chat.repository.ChatMembersRepository;
import com.DOCKin.chat.repository.ChatMessagesRepository;
import com.DOCKin.chat.repository.ChatRoomsRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 채팅방 수정·나가기의 입력 오류와 방장 승계 (#102, #106). */
@ExtendWith(MockitoExtension.class)
class ChatRoomServiceTest {

    private static final int ROOM = 7;

    @Mock private ChatRoomsRepository chatRoomsRepository;
    @Mock private ChatMembersRepository chatMembersRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private ChatMessagesRepository chatMessagesRepository;
    @Mock private ChatJdbcRepository chatJdbcRepository;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks private ChatRoomService chatRoomService;

    private static ChatRooms room(String creatorId) {
        return ChatRooms.builder().roomId(ROOM).roomName("방").creatorId(creatorId).members(new ArrayList<>()).build();
    }

    private static ChatMembers member(ChatRooms room, String userId) {
        return ChatMembers.builder().chatRooms(room).member(Member.builder().userId(userId).build()).build();
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    @Test
    @DisplayName("멤버가 아닌 사람이 나가면 거절한다 - 전에는 0건 삭제여도 204 (#106)")
    void nonMemberCannotLeave() {
        ChatRooms room = room("boss");
        when(chatRoomsRepository.findById(ROOM)).thenReturn(Optional.of(room));
        when(chatMembersRepository.existsByChatRoomsAndMemberUserId(room, "stranger")).thenReturn(false);

        assertThatThrownBy(() -> chatRoomService.leaveChatRoom(ROOM, "stranger"))
                .isInstanceOf(BusinessException.class)
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.CHATROOM_AUTHOR));
        verify(chatMembersRepository, never()).deleteByChatRoomsAndMemberUserId(any(), anyString());
    }

    @Test
    @DisplayName("방장이 나가면 가장 먼저 들어온 멤버가 방장이 된다 (#106)")
    void creatorLeavingTransfersOwnership() {
        ChatRooms room = room("boss");
        when(chatRoomsRepository.findById(ROOM)).thenReturn(Optional.of(room));
        when(chatMembersRepository.existsByChatRoomsAndMemberUserId(room, "boss")).thenReturn(true);
        when(chatMembersRepository.countByChatRooms(room)).thenReturn(2L);
        when(chatMembersRepository.findFirstByChatRoomsOrderByJoinedAtAscIdAsc(room))
                .thenReturn(Optional.of(member(room, "early")));

        chatRoomService.leaveChatRoom(ROOM, "boss");

        verify(chatMembersRepository).deleteByChatRoomsAndMemberUserId(room, "boss");
        assertThat(room.getCreatorId()).isEqualTo("early");
        verify(chatRoomsRepository, never()).delete(any());
    }

    @Test
    @DisplayName("방장이 아닌 멤버가 나가면 방장은 그대로다")
    void memberLeavingKeepsCreator() {
        ChatRooms room = room("boss");
        when(chatRoomsRepository.findById(ROOM)).thenReturn(Optional.of(room));
        when(chatMembersRepository.existsByChatRoomsAndMemberUserId(room, "worker")).thenReturn(true);
        when(chatMembersRepository.countByChatRooms(room)).thenReturn(1L);

        chatRoomService.leaveChatRoom(ROOM, "worker");

        assertThat(room.getCreatorId()).isEqualTo("boss");
        verify(chatMembersRepository, never()).findFirstByChatRoomsOrderByJoinedAtAscIdAsc(any());
    }

    @Test
    @DisplayName("마지막 멤버가 나가면 방을 지운다")
    void lastMemberLeavingDeletesRoom() {
        ChatRooms room = room("boss");
        when(chatRoomsRepository.findById(ROOM)).thenReturn(Optional.of(room));
        when(chatMembersRepository.existsByChatRoomsAndMemberUserId(room, "boss")).thenReturn(true);
        when(chatMembersRepository.countByChatRooms(room)).thenReturn(0L);

        chatRoomService.leaveChatRoom(ROOM, "boss");

        verify(chatRoomsRepository).delete(room);
    }

    @Test
    @DisplayName("수정 후 멤버가 2명 미만이면 500이 아니라 CHATROOM_MIN_MEMBERS (#102)")
    void reviseBelowMinimumIsClientError() {
        ChatRooms room = room("boss");
        when(chatRoomsRepository.findById(ROOM)).thenReturn(Optional.of(room));
        when(chatMembersRepository.countByChatRooms(room)).thenReturn(1L);
        ChatRoomUpdateRequestDto dto = ChatRoomUpdateRequestDto.builder()
                .removeParticipantIds(java.util.List.of("worker")).build();

        assertThatThrownBy(() -> chatRoomService.reviseChatRoom(ROOM, "boss", dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.CHATROOM_MIN_MEMBERS));
    }

    @Test
    @DisplayName("빈 방 이름은 500이 아니라 CHATROOM_NAME_REQUIRED (#102)")
    void blankRoomNameIsClientError() {
        ChatRooms room = room("boss");
        when(chatRoomsRepository.findById(ROOM)).thenReturn(Optional.of(room));
        ChatRoomUpdateRequestDto dto = ChatRoomUpdateRequestDto.builder().roomName("  ").build();

        assertThatThrownBy(() -> chatRoomService.reviseChatRoom(ROOM, "boss", dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.CHATROOM_NAME_REQUIRED));
    }

    @Test
    @DisplayName("creator_id가 null인 방은 NPE 대신 권한 없음 (#102)")
    void nullCreatorIsNotAnNpe() {
        ChatRooms room = room(null);
        when(chatRoomsRepository.findById(ROOM)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> chatRoomService.reviseChatRoom(ROOM, "boss", new ChatRoomUpdateRequestDto()))
                .isInstanceOf(BusinessException.class)
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.CHATROOM_AUTHOR));
        assertThatThrownBy(() -> chatRoomService.deleteChatRoom(ROOM, "boss"))
                .isInstanceOf(BusinessException.class);
    }
}
