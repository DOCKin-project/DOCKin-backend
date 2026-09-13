package com.DOCKin.global.file;

import com.DOCKin.absence.repository.AbsenceRequestRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.worklog.repository.WorkLogRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;

/**
 * S3 객체 다운로드. <b>요청자가 볼 수 있는 레코드에 붙은 키만</b> 내준다.
 *
 * <h3>이전에는 버킷 전체가 열려 있었다 (백로그 P2-18-7)</h3>
 * 인증만 있으면 아무 키나 받을 수 있었다 — 휴가 증빙서류(진단서 등)가 포함된다. 키가
 * UUID라 추측은 어렵지만, 응답 DTO({@code documentUrl}·{@code imageUrls})에 URL이 그대로
 * 실려 나가므로 한 번 본 사람은 키를 안다. "추측하기 어렵다"는 권한이 아니다.
 *
 * <h3>권한은 업로드한 곳의 규칙을 따른다</h3>
 * 키 자체에는 주인이 없다. 그 키를 가리키는 레코드가 누구에게 보이는가가 곧 파일의 권한이다.
 * <ul>
 *   <li>작업일지 사진 — 그 작업일지가 <b>같은 구역</b>이면 본다 (목록·검색과 같은 범위)</li>
 *   <li>휴가 증빙 — <b>본인</b> 또는 <b>ADMIN</b> (신청 목록과 승인 화면이 그렇다)</li>
 * </ul>
 * 어느 레코드에도 붙어 있지 않은 키는 없는 것으로 답한다({@code 404}). "있는데 못 본다"를
 * 따로 답하면 존재 여부가 샌다.
 *
 * <h3>키의 꼴을 먼저 본다</h3>
 * 업로드가 만드는 키는 {@code UUID.확장자}뿐이다({@link S3PresignedService#OBJECT_KEY}). 그 밖의
 * 것은 검사 없이 거부한다 — {@code Content-Disposition}에 따옴표·개행이 들어가던 헤더 인젝션이
 * 여기서 같이 막힌다. 헤더는 스프링의 {@link ContentDisposition}으로 만들어 이스케이프까지 맡긴다.
 */
@Service
@RequiredArgsConstructor
public class SpringFileDownloadService {

    private final S3Processor s3Processor;
    private final MemberRepository memberRepository;
    private final WorkLogRepository workLogRepository;
    private final AbsenceRequestRepository absenceRequestRepository;

    @Value("${S3_BUCKET_NAME}")
    private String bucketName;

    @Transactional(readOnly = true)
    public Resource getFileResource(String objectKey, String requesterId, HttpServletResponse response) {
        if (objectKey == null || !S3PresignedService.OBJECT_KEY.matcher(objectKey).matches()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        // 구역은 principal에 없다. 작업일지 목록(WorkLogsService.readWorklog)과 같이 다시 읽는다.
        Member requester = memberRepository.findByUserId(requesterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!canRead(objectKey, requester)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        InputStream inputStream = s3Processor.getObjectBytes(bucketName, objectKey);

        // 키는 위 정규식으로 ASCII만 통과했으므로 인코딩 없이 그대로 쓴다.
        response.setHeader("Content-Disposition", ContentDisposition.attachment()
                .filename(objectKey)
                .build()
                .toString());
        // 이전에는 image/png 고정이었다. PDF도 오가는 경로다.
        response.setContentType(MediaTypeFactory.getMediaType(objectKey)
                .orElse(MediaType.APPLICATION_OCTET_STREAM)
                .toString());
        return new InputStreamResource(inputStream);
    }

    private boolean canRead(String objectKey, Member requester) {
        String suffix = "/" + objectKey;
        if (workLogRepository.imageVisibleFromArea(objectKey, requester.getShipYardArea())) {
            return true;
        }
        if (requester.getRole() == UserRole.ADMIN) {
            return absenceRequestRepository.existsByDocumentUrlEndingWith(suffix);
        }
        return absenceRequestRepository.existsByMember_UserIdAndDocumentUrlEndingWith(requester.getUserId(), suffix);
    }
}
