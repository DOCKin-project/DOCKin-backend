package com.DOCKin.chat.controller;

import com.DOCKin.chat.dto.ChatMessageResponseDto;
import com.DOCKin.chat.dto.ChatRoomRequestDto;
import com.DOCKin.chat.dto.ChatRoomResponseDto;
import com.DOCKin.chat.dto.ChatRoomUpdateRequestDto;
import com.DOCKin.global.security.auth.CustomUserDetails;
import com.DOCKin.chat.service.ChatRoomService;
import com.DOCKin.chat.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import com.DOCKin.chat.dto.ChatReadRequestDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;



@Tag(name="채팅방 관리", description = "채팅방 관리")
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatRoomController {
    private final ChatRoomService chatRoomService;
    private final ChatService chatService;

    @Operation(summary="채팅방 생성", description = "새로운 채팅방을 생성함")
    @PostMapping("/room")
    public ResponseEntity<ChatRoomResponseDto> createRoom(@Valid @RequestBody ChatRoomRequestDto dto, @AuthenticationPrincipal CustomUserDetails customUserDetails){
        String creatorId = customUserDetails.getMember().getUserId();
        ChatRoomResponseDto chatRooms = chatRoomService.createChatRoom(dto,creatorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(chatRooms);
    }

    @Operation(summary="채팅방 전체 목록 조회",
            description = "내 채팅방 목록. 최근 메시지 순. unreadCount = lastMessageSeq − 내 lastReadSeq. sort 파라미터는 받지 않는다")
    @GetMapping("/rooms")
    public ResponseEntity<Page<ChatRoomResponseDto>> findAllRooms(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            // 정렬은 SQL이 정한다(last_message_at DESC, room_id DESC). 여기 sort는 선언용이다 —
            // PageableSortDefaultTest가 "정렬 없는 페이징"을 잡으므로 무엇으로 정렬되는지를 계약에 적어 둔다.
            @PageableDefault(size = 20, sort = "lastMessageAt", direction = Sort.Direction.DESC) Pageable pageable
            ) {
        String userId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(chatRoomService.getChatRooms(userId,pageable));
    }

    @Operation(summary="채팅방 상세 조회", description="특정 채팅방 상세 조회. 읽음 처리를 하지 않는다 - PATCH /room/{roomId}/read를 쓴다")
    @GetMapping("/room/{roomId}")
    public ResponseEntity<ChatRoomResponseDto> roomInfo(@PathVariable Integer roomId,
                                                        @AuthenticationPrincipal CustomUserDetails customUserDetails){
        String userId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(chatRoomService.getChatRoomsInfo(userId,roomId));
    }


    @Operation(summary="채팅방 수정", description ="특정 채팅방 이름 수정")
    @PutMapping("/room/{roomId}")
    public ResponseEntity<ChatRoomResponseDto> updateRoom(
            @PathVariable Integer roomId,
           @Valid @RequestBody ChatRoomUpdateRequestDto updateDto,
            @AuthenticationPrincipal CustomUserDetails customUserDetails){
        String creatorId = customUserDetails.getMember().getUserId();
        ChatRoomResponseDto dto =chatRoomService.reviseChatRoom(roomId,creatorId,updateDto);

        return ResponseEntity.ok(dto);
    }

    @Operation(summary="채팅방 삭제",description = "채팅방을 삭제")
    @DeleteMapping("/room/{roomId}")
    public ResponseEntity<Void> deleteRoom(@PathVariable Integer roomId,
                                           @AuthenticationPrincipal CustomUserDetails customUserDetails){
        String creatorId = customUserDetails.getMember().getUserId();
        chatRoomService.deleteChatRoom(roomId,creatorId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary="채팅방 나가기",description = "채팅방을 나간다")
    @DeleteMapping("/room/leave/{roomId}")
    public ResponseEntity<Void> leaveRoom(@PathVariable Integer roomId,
                                           @AuthenticationPrincipal CustomUserDetails customUserDetails){
        String userId = customUserDetails.getMember().getUserId();
        chatRoomService.leaveChatRoom(roomId,userId);

        return ResponseEntity.noContent().build();
    }

    @Operation(summary="채팅 내역 조회 (위로 스크롤)",
            description = "beforeSeq보다 작은 메시지를 최신순으로. 첫 페이지는 beforeSeq 없이. 입장 이후 메시지만. "
                    + "lastMessageId는 옛 커서로, beforeSeq가 없을 때만 본다")
    @GetMapping("/room/{roomId}/messages")
    public ResponseEntity<Slice<ChatMessageResponseDto>> getChatMessages(@PathVariable Integer roomId,
                                                                         @AuthenticationPrincipal CustomUserDetails customUserDetails,
                                                                         @RequestParam(required = false) Long beforeSeq,
                                                                         @Deprecated @RequestParam(required = false) Long lastMessageId,
                                                                         @PageableDefault(size = 20, sort = "roomSeq", direction = Sort.Direction.DESC) Pageable pageable){
       String userId = customUserDetails.getMember().getUserId();
        Slice<ChatMessageResponseDto> chatHistory = chatService.getChatHistory(roomId, userId, beforeSeq, lastMessageId, pageable);
        return ResponseEntity.ok(chatHistory);
    }

    @Operation(summary="따라잡기 (재접속 후)",
            description = "seq보다 큰 메시지를 오래된 순으로. 끊긴 동안 놓친 것을 받는다. hasNext면 마지막 원소의 roomSeq로 이어 부른다")
    @GetMapping("/room/{roomId}/messages/after")
    public ResponseEntity<Slice<ChatMessageResponseDto>> catchUp(@PathVariable Integer roomId,
                                                                 @AuthenticationPrincipal CustomUserDetails customUserDetails,
                                                                 @RequestParam @PositiveOrZero long seq,
                                                                 @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit){
        String userId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(chatService.catchUp(roomId, userId, seq, limit));
    }

    @Operation(summary="읽음 처리",
            description = "여기까지 읽었다 - roomSeq로. 올리기만 하므로 재시도·역순 도착에 안전하다. 방 상세 조회는 더 이상 읽음 처리를 하지 않는다")
    @PatchMapping("/room/{roomId}/read")
    public ResponseEntity<Void> markRead(@PathVariable Integer roomId,
                                         @AuthenticationPrincipal CustomUserDetails customUserDetails,
                                         @Valid @RequestBody ChatReadRequestDto body){
        String userId = customUserDetails.getMember().getUserId();
        chatService.markRead(roomId, userId, body.upToSeq());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary ="채팅방에서 특정 키워드 조회")
    @GetMapping("/room/{roomId}/messages/search")
    public ResponseEntity<Slice<ChatMessageResponseDto>> searchMessages(@PathVariable Integer roomId,
                                                                        @RequestParam String keyword,
                                                                        @AuthenticationPrincipal CustomUserDetails customUserDetails,
                                                                        @PageableDefault(sort = "roomSeq", direction = Sort.Direction.DESC) Pageable pageable){

        String userId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(chatService.searchMessage(roomId,userId,keyword,pageable));
    }
}
