package com.DOCKin.worklog.model;

import com.DOCKin.member.model.Member;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "work_logs")
public class WorkLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long logId;

    @Column(nullable = false,length = 256)
    private String title;

    /**
     * 작업일지 본문.
     *
     * <h4>{@code @Lob}을 제거했다 — PostgreSQL에서 읽기가 통째로 깨져 있었다</h4>
     * {@code @Lob String}은 Hibernate가 {@code Types.CLOB}으로 매핑하는데,
     * <b>PgJDBC는 CLOB을 Large Object의 OID(숫자 참조)로 취급한다.</b>
     * 그래서 {@code text} 컬럼을 읽을 때 그 내용을 {@code long}으로 파싱하려다 실패한다:
     * <pre>Bad value for type long : 오전 작업 시작 직후부터 용접 와이어가...</pre>
     *
     * <p>MySQL에서는 {@code @Lob String}이 {@code LONGTEXT}로 매핑되어 잘 돌았다.
     * {@code SafetyCourse.createdAt}의 {@code columnDefinition = "DATETIME"}과 같은 부류로,
     * <b>이관 때 살아남은 MySQL 흔적</b>이다.
     *
     * <h4>왜 지금까지 안 드러났나</h4>
     * 두 겹으로 가려져 있었다.
     * <ul>
     *   <li>{@code columnDefinition = "TEXT"}가 DDL을 강제하므로 <b>테이블은 정상적으로 text다.</b>
     *       {@code ddl-auto=validate}는 컬럼 타입만 보고 JDBC 바인딩은 보지 않아 통과한다
     *       ({@code SchemaValidationTest}도 마찬가지다)</li>
     *   <li>{@code work_logs}가 계속 비어 있어 <b>조회가 행을 하나도 반환한 적이 없었다.</b>
     *       터지려면 실제 행을 읽어야 한다</li>
     * </ul>
     * 시드 데이터를 넣고 색인을 처음 돌린 순간 드러났다 — 데이터가 없으면 못 찾는 결함이다.
     *
     * <p>{@code columnDefinition = "TEXT"}는 그대로 둔다. 애노테이션 없이도 이 값이 DDL을
     * 결정하므로 스키마는 바뀌지 않고, 바인딩만 일반 문자열로 돌아온다.
     */
    @Column(name = "log_text", columnDefinition = "TEXT", nullable = false)
    private String logText;

    private String audioFileUrl;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 작성자. <b>필수다</b>(P2-15-6, {@code V5__work_logs_user_id_not_null.sql}).
     *
     * <p>스키마상 nullable이었으나 실제 값은 20,016행 중 NULL이 0건이었고, 생성 경로 둘
     * 모두 {@code member}를 필수로 요구하며, {@code users} 삭제도 FK가 NO ACTION이라 막힌다.
     * 즉 <b>"작성자 없는 작업일지"는 실재한 적이 없다.</b> 컬럼을 NOT NULL로 바꿔
     * {@code WorkLogDto.from}이 이 값을 무조건 역참조해도 되는 근거를 만들었다 —
     * 방어 코드를 넣는 대신 방어할 상태를 없앴다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private Member member;

    /**
     * 관련 장비. <b>선택이다</b> — 바로 위와 정반대이고, 그것이 P2-15-2의 결함이 나온 자리다.
     *
     * <p>같은 {@code @ManyToOne(LAZY)}인데 FK가 NULL이면 프록시가 아니라 <b>null이 들어온다.</b>
     * 같은 20,016행에서 이쪽은 NULL이 3,331건이다. {@code WorkLogDto.from}이 이 값을
     * 무조건 역참조해 페이지 전체를 500으로 만들었고, 지금은 null 검사가 있다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "equipment_id")
    private Equipment equipment;

    @Builder.Default
    @OneToMany(mappedBy = "logId",cascade=CascadeType.ALL,orphanRemoval = true)
    private List<Comment> comments =new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "workLog", cascade = CascadeType.ALL,orphanRemoval = true)
    private List<WorkLogImage> images = new ArrayList<>();

    public void addImage(WorkLogImage image){
        this.images.add(image);
        image.setWorkLog(this);
    }

}
