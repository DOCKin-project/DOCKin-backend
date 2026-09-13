package com.DOCKin.chat.model;

import com.DOCKin.chat.dto.MessageType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.generator.EventType;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@AllArgsConstructor
@Builder
@Table(name = "chat_messages",
        // V6: 순서의 축은 room_seq다. (room_id, room_seq) 유니크가 곧 따라잡기 인덱스이고,
        // 옛 (room_id, sent_at) 인덱스는 V6가 내렸다.
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_chat_messages_room_seq", columnNames = {"room_id", "room_seq"}),
                @UniqueConstraint(name = "uq_chat_messages_client_msg_id", columnNames = {"room_id", "client_msg_id"})
        })
public class ChatMessages {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ChatRooms chatRooms;

    @Column(name = "sender_id")
    @NotNull
    private String senderId;

    @Column(name = "content", columnDefinition = "TEXT")
    @NotNull
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type")
    @NotNull
    private MessageType messageType;

    @Column(name = "file_url")
    private String fileUrl;

    /**
     * 시계는 DB 하나다(ADR-0008 D6). 앱이 값을 넣지 않고 {@code DEFAULT now()}(V6)가 채우며,
     * Hibernate가 INSERT 뒤 {@code RETURNING}으로 읽어 온다. 예전에는 {@code @PrePersist}가 앱 시각을
     * 넣어 {@code last_message_at}·{@code last_read_time}(DB {@code NOW()})과 시계가 둘이었다(P2-12-2).
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "sent_at", insertable = false, updatable = false)
    private LocalDateTime sentAt;

    /**
     * 방 안에서의 순번. <b>이것이 순서·읽음·따라잡기의 축이다</b>(ADR-0008 D7·D8, V6).
     *
     * <p>{@code message_id}는 그 축이 될 수 없다 — {@code IDENTITY}는 INSERT 때 번호를 주고 커밋은
     * 그 뒤라, 큰 번호가 먼저 커밋되면 "본 것보다 큰 ID"로 따라잡는 커서가 작은 번호를 영영 못 본다.
     * {@code MessageIdCommitOrderMeasurementTest}가 실제 저장 경로 모양에서 56~78%를 놓치는 것을 보였다.
     *
     * <p>값은 {@code ChatJdbcRepository.nextRoomSeq}가 {@code chat_rooms} 행 락 안에서 발급한다.
     * 그 락은 {@code last_message_*}를 갱신하려고 원래 잡던 것이라 새 비용이 아니며,
     * 락 순서 = 커밋 순서 = 번호 순서가 성립한다(같은 테스트에서 유실 0).
     */
    @Column(name = "room_seq", nullable = false)
    private Long roomSeq;

    /**
     * 클라이언트가 발급하는 재전송 키(ADR-0008 D9). STOMP는 전달을 보장하지 않으므로 응답을 못 받은
     * 클라이언트가 재전송하면 같은 메시지가 두 번 저장된다 — ADR-0001의 출근 더블클릭과 같은 문제다.
     * {@code UNIQUE(room_id, client_msg_id)}가 막는다. 키 없이 보내는 옛 클라이언트는 NULL이며,
     * PostgreSQL은 NULL끼리를 다른 값으로 보므로 막히지 않는다.
     */
    @Column(name = "client_msg_id")
    private UUID clientMsgId;

    /**
     * 원문의 언어(P2-8-5). 발신자의 {@code users.language_code}를 기본값으로 넣는다 — 가장 싼 근사치이고,
     * 소급이 불가능한 값이라 번역 기능(D3)보다 먼저 남긴다.
     */
    @Column(name = "language_code", length = 8)
    private String languageCode;

    @PrePersist
    public void prePersist() {
        if (this.messageType == null) {
            this.messageType = MessageType.TEXT; // 기본값 설정
        }
    }
}
