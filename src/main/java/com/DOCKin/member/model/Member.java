package com.DOCKin.member.model;

import jakarta.persistence.*;
import lombok.*;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor(access= AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name="users")
public class Member {
    @Id
    @Column(name = "user_id", length = 50)
    private String userId;

    @Column(nullable=false, length = 10)
    private String name;

    @Column(nullable=false, length = 256)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable=false)
    private UserRole role;

    @Column(nullable=false)
    private String language_code;

    @Column(nullable=false)
    private Boolean tts_enabled;

    @CreationTimestamp
    @Column(updatable=false, nullable = false)
    private LocalDateTime created_at;

    @Column(nullable = false, length = 100)
    private String shipYardArea;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private WorkShift workShift = WorkShift.MORNING;

    // 연차 정책 확정 전까지의 잠정 기본값 (WorkShift와 동일한 패턴)
    @Column(name = "remaining_leave_days", nullable = false)
    @Builder.Default
    private Integer remainingLeaveDays = 15;

    /**
     * 잔여 연차를 차감한다.
     *
     * <p>차감 가능 여부를 여기서도 검사하는 것은 <b>불변식을 데이터 곁에 두기 위해서다.</b>
     * 호출자(승인 로직)가 이미 검사하지만, 그 검사는 사용자에게 적절한 오류를 주기 위한 것이고
     * 이 검사는 <b>어떤 경로로 들어와도 잔액이 음수가 되지 않게</b> 보장한다.
     * 호출부가 늘어나면 검사를 빠뜨리는 곳이 생기기 마련이다.
     *
     * <p>다만 이것만으로 동시성이 해결되지는 않는다. 두 트랜잭션이 각각 낡은 잔액을 읽으면
     * 둘 다 이 검사를 통과한다. 그래서 조회 시점에 비관적 락이 필요하다
     * ({@code MemberRepository.findByUserIdForUpdate}).
     */
    public void useLeaveDays(int days) {
        if (days > this.remainingLeaveDays) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_LEAVE_DAYS);
        }
        this.remainingLeaveDays -= days;
    }

}
