package com.DOCKin.rag.service;

import com.DOCKin.rag.model.Visibility;
import com.DOCKin.worklog.model.WorkLog;
import com.DOCKin.worklog.model.WorkLogStatus;

/**
 * 작업일지(와 그 번역본) 청크의 공개 범위를 정하는 <b>유일한</b> 규칙 (ADR-0012).
 *
 * <ul>
 *   <li>{@code APPROVED} → {@link Visibility#AREA}, 작성자의 구역</li>
 *   <li>그 외({@code PENDING}·{@code REJECTED}) → {@link Visibility#OWNER}</li>
 * </ul>
 *
 * <p>새벽 배치({@code IndexingService.toTarget})와 상태가 바뀌는 순간({@code ChunkIndexWriter.syncWorkLogAccess})이
 * 둘 다 이것을 부른다. 두 곳이 규칙을 따로 가지면 배치가 돌 때마다 즉시 반영한 값을 되돌릴 수 있다.
 *
 * <p>소유자는 상태와 무관하게 항상 작성자다. 작성자 본인 조회는 {@code OWNER}·{@code AREA} 둘 다
 * 소유자 조건으로도 걸리지만, 검색은 {@code OWNER}와 {@code AREA}를 따로 뽑으므로 겹치지 않는다.
 */
public record WorkLogChunkAccess(Visibility visibility, String ownerUserId, String area) {

    public static WorkLogChunkAccess of(WorkLog workLog) {
        // getMember()는 non-null이다 — work_logs.user_id가 NOT NULL(V5).
        String owner = workLog.getMember().getUserId();
        if (workLog.getStatus() == WorkLogStatus.APPROVED) {
            return new WorkLogChunkAccess(Visibility.AREA, owner, workLog.getMember().getShipYardArea());
        }
        return new WorkLogChunkAccess(Visibility.OWNER, owner, null);
    }
}
