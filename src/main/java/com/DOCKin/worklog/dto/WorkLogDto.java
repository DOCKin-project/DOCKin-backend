package com.DOCKin.worklog.dto;

import com.DOCKin.worklog.model.WorkLogImage;
import com.DOCKin.worklog.model.WorkLog;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "작업 일지 조회 res dto")
public class WorkLogDto {
    @Schema(description = "작업 일지 고유 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long logId;

    @Schema(description = "작성자 사원번호",requiredMode = Schema.RequiredMode.REQUIRED)
    private String userId;

    @Schema(description = "관련 장비 ID", example = "50")
    private Long equipmentId;

    @Schema(description = "일지 제목",requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @Schema(description = "일지 상세 내용")
    private String logText;

    @Schema(description = "첨부 이미지 URL")
    private List<String> imageUrls;

    @Schema(description = "작성 일시", example = "2026-01-12T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createdAt;

    @Schema(description = "수정 일시", example = "2026-01-12T15:30:00")
    private LocalDateTime updatedAt;

    private String audioFileUrl;

    /**
     * 엔티티를 응답 DTO로 옮긴다.
     *
     * <h4>{@code equipment}가 null일 수 있다 — 그리고 그것이 정상이다</h4>
     * {@code work_logs.equipment_id}는 {@code V2__baseline_existing_tables.sql}에서
     * <b>nullable</b>이고, 이 클래스의 {@code equipmentId}도 {@code requiredMode}가 붙지 않은
     * <b>선택 필드</b>다. 즉 "장비가 지정되지 않은 작업일지"는 스키마와 API 계약이 둘 다
     * 허용하는 상태다. 그런데 이 메서드가 {@code getEquipment().getEquipmentId()}를
     * <b>무조건</b> 불러 NPE를 냈다.
     *
     * <p>{@code @ManyToOne(LAZY)}는 FK가 NULL이면 프록시가 아니라 <b>null을 넣는다.</b>
     * {@code WorkLogListQueryCountTest}가 "식별자만 읽으니 쿼리가 안 나간다"고 확인한 그 접근이,
     * NULL 앞에서는 반대로 터진다 — 안전해 보이던 이유와 터지는 이유가 같은 자리에 있다.
     *
     * <h4>영향 범위가 행 하나가 아니라 페이지였다</h4>
     * 목록은 {@code Page.map(WorkLogDto::from)}으로 만들어진다. {@code map}은 한 행에서 터지면
     * 거기서 멈추므로 <b>장비 없는 일지 한 건이 그 페이지를 통째로 못 보게 만든다.</b>
     * 그리고 이것은 {@code NoResourceFoundException}처럼 404로 내려보낼 것이 아니라
     * <b>진짜 500</b>이었다.
     *
     * <h4>왜 지금까지 안 드러났나</h4>
     * 생성 API로는 이 상태가 만들어지지 않는다. {@code WorkLogsService.createWorklog}가
     * {@code equipmentRepository.findById(...)}로 장비를 필수로 요구하기 때문에
     * <b>API만 두드려서는 영영 나오지 않는다.</b> 반면 컬럼은 NULL을 허용하고,
     * 같은 서비스의 {@code updateWorklog}는 이미 {@code dto.getEquipmentId() != null}로
     * 장비를 <b>선택</b>으로 다루고 있었다 — 쓰기 경로 안에서도 필수와 선택이 갈려 있었다.
     *
     * <p>{@code @Lob}이 {@code validate}를 통과하던 것과 같은 부류다.
     * <b>스키마가 허용하는 상태를 코드가 가정으로 배제하고 있었고, 그 가정을 만드는 데이터가
     * 없어서 조용했다.</b>
     *
     * <h4>{@code member}는 반대 방향으로 고쳤다 — 가드가 아니라 NOT NULL이다</h4>
     * {@code user_id}도 스키마상 nullable이라 바로 윗줄이 같은 자리에 있었지만
     * <b>고칠 방향이 반대였다.</b> {@code equipmentId}는 계약이 선택 필드라 null이 정당한
     * 값이지만, {@code userId}는 {@code requiredMode = REQUIRED}다. 여기에 null 가드를 두면
     * <b>필수 필드에 null을 담은 응답을 조용히 내보내게 된다.</b>
     *
     * <p>그래서 P2-15-6이 컬럼을 {@code NOT NULL}로 바꿨다
     * ({@code V5__work_logs_user_id_not_null.sql}). 데이터가 그 방향을 지지했다 —
     * 같은 20,016행에서 {@code user_id}는 NULL이 <b>0건</b>이고 {@code equipment_id}는
     * <b>3,331건</b>이다. 스키마에서는 똑같이 nullable인 두 컬럼인데 실제 값은 정반대다.
     *
     * <p><b>그래서 아래 {@code getMember()}에는 검사가 없다.</b> 빠뜨린 것이 아니라
     * 방어할 상태 자체가 없어진 것이다.
     */
    public static WorkLogDto from(WorkLog entity) {
        return WorkLogDto.builder()
                .logId(entity.getLogId())
                .userId(entity.getMember().getUserId())
                .equipmentId(entity.getEquipment() == null
                        ? null
                        : entity.getEquipment().getEquipmentId())
                .title(entity.getTitle())
                .logText(entity.getLogText())
                .imageUrls(entity.getImages().stream()
                        .map(WorkLogImage::getImageUrl)
                        .collect(Collectors.toList()))
                .audioFileUrl(entity.getAudioFileUrl())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
