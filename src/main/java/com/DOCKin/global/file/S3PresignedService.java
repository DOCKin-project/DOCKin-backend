package com.DOCKin.global.file;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class S3PresignedService {
    private final S3Processor s3Processor;

    @Value("${S3_BUCKET_NAME}")
    private String bucketName;

    public S3PresignedResponse getPresignedUrl(String originalFileName){
        String extension = originalFileName.substring(originalFileName.lastIndexOf("."));
        String uniqueFileName = UUID.randomUUID()+ extension;
        String url = s3Processor.getPresignedUrl(bucketName,uniqueFileName);

        return new S3PresignedResponse(url, uniqueFileName);
    }
}
