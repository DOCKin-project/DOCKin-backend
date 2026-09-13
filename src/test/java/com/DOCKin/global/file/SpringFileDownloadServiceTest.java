package com.DOCKin.global.file;

import com.DOCKin.absence.repository.AbsenceRequestRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.worklog.repository.WorkLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 검증: 다운로드가 <b>요청자가 볼 수 있는 레코드에 붙은 키만</b> 내준다 (백로그 P2-18-7).
 *
 * <p>이전에는 인증만 있으면 버킷의 아무 키나 받았다. 여기서 보는 것은 세 가지다 —
 * 어느 레코드에도 없는 키는 S3에 닿기 전에 404, 휴가 증빙은 본인·ADMIN만, 꼴이 다른 키는
 * 검사 없이 404(헤더 인젝션이 여기서 같이 막힌다).
 */
@ExtendWith(MockitoExtension.class)
class SpringFileDownloadServiceTest {

    private static final String KEY = "0f8fad5b-d9cb-469f-a165-70867728950e.pdf";
    private static final String OTHER_KEY = "1f8fad5b-d9cb-469f-a165-70867728950e.jpg";

    @Mock
    private S3Processor s3Processor;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private WorkLogRepository workLogRepository;
    @Mock
    private AbsenceRequestRepository absenceRequestRepository;

    @InjectMocks
    private SpringFileDownloadService service;

    @Test
    @DisplayName("어느 레코드에도 붙어 있지 않은 키는 404 - S3에 닿지 않는다")
    void 주인_없는_키() {
        stubRequester("u1", UserRole.USER, "A");
        when(workLogRepository.imageVisibleFromArea(KEY, "A")).thenReturn(false);
        when(absenceRequestRepository.existsByMember_UserIdAndDocumentUrlEndingWith("u1", "/" + KEY)).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.getFileResource(KEY, "u1", new MockHttpServletResponse()));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, e.getErrorCode());
        verify(s3Processor, never()).getObjectBytes(any(), any());
    }

    @Test
    @DisplayName("같은 구역 작업일지의 사진은 받는다 - 헤더는 스프링이 만든다")
    void 구역_사진() {
        stubRequester("u1", UserRole.USER, "A");
        when(workLogRepository.imageVisibleFromArea(OTHER_KEY, "A")).thenReturn(true);
        when(s3Processor.getObjectBytes(any(), any())).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertNotNull(service.getFileResource(OTHER_KEY, "u1", response));

        assertEquals("attachment; filename=\"" + OTHER_KEY + "\"", response.getHeader("Content-Disposition"));
        assertEquals("image/jpeg", response.getContentType(), "이전에는 image/png 고정이었다");
        // 사진이 보이면 휴가 쪽은 묻지 않는다.
        verify(absenceRequestRepository, never()).existsByMember_UserIdAndDocumentUrlEndingWith(any(), any());
    }

    @Test
    @DisplayName("휴가 증빙은 본인만 - 남의 것은 같은 구역이어도 404")
    void 휴가_증빙_본인만() {
        stubRequester("u1", UserRole.USER, "A");
        when(workLogRepository.imageVisibleFromArea(KEY, "A")).thenReturn(false);
        when(absenceRequestRepository.existsByMember_UserIdAndDocumentUrlEndingWith("u1", "/" + KEY)).thenReturn(false);

        assertThrows(BusinessException.class,
                () -> service.getFileResource(KEY, "u1", new MockHttpServletResponse()));
        // 일반 사용자는 전체 조회 쪽(ADMIN용)을 타지 않는다.
        verify(absenceRequestRepository, never()).existsByDocumentUrlEndingWith(any());
    }

    @Test
    @DisplayName("ADMIN은 누구의 휴가 증빙이든 받는다 - 승인 화면이 그렇다")
    void 휴가_증빙_관리자() {
        stubRequester("admin", UserRole.ADMIN, "HQ");
        when(workLogRepository.imageVisibleFromArea(KEY, "HQ")).thenReturn(false);
        when(absenceRequestRepository.existsByDocumentUrlEndingWith("/" + KEY)).thenReturn(true);
        when(s3Processor.getObjectBytes(any(), any())).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertNotNull(service.getFileResource(KEY, "admin", response));

        assertEquals("application/pdf", response.getContentType());
    }

    @Test
    @DisplayName("업로드가 만드는 꼴(UUID.확장자)이 아닌 키는 검사 없이 404 - 헤더 인젝션이 여기서 막힌다")
    void 키_꼴() {
        for (String bad : new String[]{
                "../../etc/passwd", "x\".pdf", "a.pdf\r\nSet-Cookie: x", "", "0f8fad5b.pdf",
                "0f8fad5b-d9cb-469f-a165-70867728950e", "0f8fad5b-d9cb-469f-a165-70867728950e.PDF"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.getFileResource(bad, "u1", response), bad);
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, e.getErrorCode(), bad);
            assertFalse(response.containsHeader("Content-Disposition"), bad);
        }
        // 꼴이 틀리면 사용자 조회조차 없다.
        verify(memberRepository, never()).findByUserId(any());
        assertTrue(S3PresignedService.OBJECT_KEY.matcher(KEY).matches());
    }

    private void stubRequester(String userId, UserRole role, String area) {
        when(memberRepository.findByUserId(userId)).thenReturn(Optional.of(
                Member.builder().userId(userId).role(role).shipYardArea(area).build()));
    }
}
