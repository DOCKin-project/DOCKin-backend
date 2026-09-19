package com.DOCKin.worklog.service;

import com.DOCKin.ai.dto.SttDomain;
import com.DOCKin.ai.service.SttService;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.file.S3PresignedService;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.worklog.dto.WorkLogDto;
import com.DOCKin.worklog.dto.WorkLogsCreateRequestDto;
import com.DOCKin.worklog.model.Equipment;
import com.DOCKin.worklog.model.WorkLog;
import com.DOCKin.worklog.repository.EquipmentRepository;
import com.DOCKin.worklog.repository.WorkLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 음성 작업일지 작성에서 STT가 실패하면 저장하지 않는다 (#117).
 * 전에는 catch(Exception)이 삼키고 dto.logText로 대체해 저장했다.
 */
@ExtendWith(MockitoExtension.class)
class WorkLogsSttCreateTest {

    private static final String USER = "worker01";

    @Mock private WorkLogRepository workLogRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private EquipmentRepository equipmentRepository;
    @Mock private SttService sttService;
    @Mock private S3PresignedService s3PresignedService;

    @InjectMocks private WorkLogsService workLogsService;

    private final MockMultipartFile audio = new MockMultipartFile("file", "a.m4a", "audio/mp4", new byte[]{1});

    private WorkLogsCreateRequestDto request() {
        return WorkLogsCreateRequestDto.builder()
                .title("CO2 용접기 3호기").logText("(앱이 채운 자리표시자)").equipmentId(1L).build();
    }

    private void stubLookups() {
        when(memberRepository.findByUserId(USER))
                .thenReturn(Optional.of(Member.builder().userId(USER).role(UserRole.USER).build()));
        when(equipmentRepository.findById(1L)).thenReturn(Optional.of(Equipment.builder().build()));
    }

    @Test
    @DisplayName("STT가 성공하면 인식 결과가 본문이 된다")
    void 성공() {
        stubLookups();
        when(sttService.processStt(any(), anyString(), anyString()))
                .thenReturn(Mono.just(new SttDomain.Response("t", "와이어 송급 불량")));
        when(workLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkLogDto saved = workLogsService.createSttWorklog(USER, request(), audio, null);

        ArgumentCaptor<WorkLog> captor = ArgumentCaptor.forClass(WorkLog.class);
        verify(workLogRepository).save(captor.capture());
        assertEquals("와이어 송급 불량", captor.getValue().getLogText());
        assertEquals("와이어 송급 불량", saved.getLogText());
    }

    @Test
    @DisplayName("STT가 오류를 던지면 그대로 올라가고 저장하지 않는다 — 자리표시자로 대체하지 않는다")
    void 실패_예외() {
        stubLookups();
        when(sttService.processStt(any(), anyString(), anyString()))
                .thenReturn(Mono.error(new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE)));

        BusinessException e = assertThrows(BusinessException.class,
                () -> workLogsService.createSttWorklog(USER, request(), audio, null));

        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, e.getErrorCode());
        verify(workLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("STT가 200인데 인식 결과가 비어 있어도 저장하지 않는다")
    void 실패_빈_결과() {
        stubLookups();
        when(sttService.processStt(any(), anyString(), anyString()))
                .thenReturn(Mono.just(new SttDomain.Response("t", "  ")));

        BusinessException e = assertThrows(BusinessException.class,
                () -> workLogsService.createSttWorklog(USER, request(), audio, null));

        assertEquals(ErrorCode.STT_CONVERSION_ERROR, e.getErrorCode());
        verify(workLogRepository, never()).save(any());
    }
}
