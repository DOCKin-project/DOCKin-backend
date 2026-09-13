package com.DOCKin.global.file;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;

@Component
@RequiredArgsConstructor
@Slf4j

public class S3Processor {

    private final AmazonS3 amazonS3;

    public InputStream getObjectBytes(String bucketName, String objectKey){
        return amazonS3.getObject(bucketName,objectKey)
                .getObjectContent()
                .getDelegateStream();
    }

    /** @param contentType 내용을 보고 판정한 값. 클라이언트가 보낸 {@code file.getContentType()}은 쓰지 않는다 */
    public String uploadFile(String bucketName, String objectKey, MultipartFile file, String contentType){
        try{
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(file.getSize());
            metadata.setContentType(contentType);
            amazonS3.putObject(bucketName,objectKey,file.getInputStream(),metadata);
            return amazonS3.getUrl(bucketName,objectKey).toString();
        } catch (Exception e){
            throw new RuntimeException("S3 업로드 실패",e);
        }
    }
}
