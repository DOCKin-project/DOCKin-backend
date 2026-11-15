package com.DOCKin.rag.service;

import com.DOCKin.member.model.Member;
import com.DOCKin.rag.model.Visibility;
import com.DOCKin.worklog.model.WorkLog;
import com.DOCKin.worklog.model.WorkLogStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 배치와 상태 변화 경로가 공유하는 공개 범위 규칙 (ADR-0012). */
class WorkLogChunkAccessTest {

    private static WorkLog log(WorkLogStatus status) {
        Member author = Member.builder().userId("u1").shipYardArea("1도크").build();
        return WorkLog.builder().logId(1L).title("t").logText("x").member(author).status(status).build();
    }

    @Test
    @DisplayName("APPROVED만 작성자 구역에 AREA, 나머지는 본인만(OWNER, 구역 없음)")
    void rule() {
        assertEquals(new WorkLogChunkAccess(Visibility.AREA, "u1", "1도크"),
                WorkLogChunkAccess.of(log(WorkLogStatus.APPROVED)));
        assertEquals(new WorkLogChunkAccess(Visibility.OWNER, "u1", null),
                WorkLogChunkAccess.of(log(WorkLogStatus.PENDING)));
        assertEquals(new WorkLogChunkAccess(Visibility.OWNER, "u1", null),
                WorkLogChunkAccess.of(log(WorkLogStatus.REJECTED)));
    }
}
