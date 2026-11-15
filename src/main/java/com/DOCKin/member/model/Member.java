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

    /**
     * 탈퇴 시각(V20, #100). null이면 재직 중이다.
     *
     * <p>탈퇴는 행을 지우지 않는다. 근태·안전교육·작업일지는 퇴사 뒤에도 남아야 하는 기록이고(근태·임금 서류의 법정 보존,
     * 승인된 일지는 같은 구역의 RAG 지식 — ADR-0012), {@code users}를 참조하는 FK 9개가 그 기록을 붙잡고 있다.
     * 대신 식별 정보를 지우고({@link #withdraw}) 로그인·인증·배치·집계에서 뺀다.
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** 탈퇴 뒤 이름 자리에 남는 값. 작업일지·댓글 작성자로 이 이름이 보인다. {@code name} 길이(10) 안이다. */
    public static final String WITHDRAWN_NAME = "탈퇴회원";

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * 탈퇴 — 비활성화와 익명화. 사번({@code userId})은 기록의 FK라 남는다.
     *
     * <p>비밀번호는 BCrypt 모양이 아닌 값으로 덮는다. 로그인 경로가 탈퇴 회원을 먼저 거르지만, 어떤 경로로든
     * 비밀번호 대조에 닿으면 BCrypt 인코더가 "형식이 아니다"로 거부한다 — 어떤 평문과도 맞을 수 없다.
     */
    public void withdraw(LocalDateTime now) {
        this.name = WITHDRAWN_NAME;
        this.password = "!withdrawn";
        this.deletedAt = now;
    }

    /**
     * 근태 판정에 쓰는 교대. 컬럼이 nullable(V2)이라 필드 도입 이전 행은 null일 수 있고, 그때는 MORNING으로 본다.
     * 출근·결근 배치·휴가 반영이 전부 이 메서드를 쓴다 — 기본값 처리가 호출부마다 흩어지지 않게.
     */
    public WorkShift workShiftOrDefault() {
        return workShift != null ? workShift : WorkShift.MORNING;
    }

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

    /** 승인 취소의 환급. 차감과 같은 락({@code findByUserIdForUpdate}) 안에서 부른다. */
    public void refundLeaveDays(int days) {
        this.remainingLeaveDays += days;
    }

}
