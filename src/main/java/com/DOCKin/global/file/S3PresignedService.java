package com.DOCKin.global.file;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class S3PresignedService {

    /** 업로드 키의 꼴. 다운로드 쪽({@code SpringFileDownloadService})이 같은 꼴만 받는다. */
    static final Pattern OBJECT_KEY = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{1,8}$");

    private final S3Processor s3Processor;

    @Value("${S3_BUCKET_NAME}")
    private String bucketName;

    /**
     * 파일을 올리고 URL을 돌려준다. 비어 있으면 {@code null}.
     *
     * <p>종류는 {@link UploadedFileType}이 내용으로 가린다. 확장자와 Content-Type도 거기서 나오고,
     * 클라이언트가 보낸 파일명·헤더는 아무 데도 쓰지 않는다(P2-18-9).
     *
     * @throws BusinessException 받지 않는 종류면 {@code UNSUPPORTED_MEDIA_TYPE}(415)
     */
    public String uploadImage(MultipartFile file){
        if(file==null || file.isEmpty()) return null;

        UploadedFileType type = sniff(file)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
        String uniqueFileName = UUID.randomUUID() + "." + type.extension();
        try{
            return s3Processor.uploadFile(bucketName, uniqueFileName, file, type.contentType());
        } catch(Exception e){
            throw new RuntimeException("파일 업로드 중 오류 발생", e);
        }
    }

    private static Optional<UploadedFileType> sniff(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(UploadedFileType.SNIFF_LENGTH);
            return UploadedFileType.sniff(head);
        } catch (IOException e) {
            throw new RuntimeException("파일을 읽을 수 없습니다", e);
        }
    }
}
