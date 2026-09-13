package com.DOCKin.global.file;

import com.DOCKin.global.security.auth.CustomUserDetails;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/spring-file")
@RequiredArgsConstructor
public class SpringFileDownloadController {

    private final SpringFileDownloadService springFileDownloadService;

    /**
     * {@code GET /api/spring-file/download?key=<UUID.ext>}.
     *
     * <p>이전에는 GET에 {@code @RequestBody}로 키를 받았다 — 본문 있는 GET은 대부분의 HTTP
     * 클라이언트가 보내지 못한다. 쿼리 파라미터로 바꿨다. 권한은 서비스가 본다(P2-18-7).
     */
    @GetMapping("/download")
    public Resource downloadFile(@RequestParam("key") String objectKey,
                                 @AuthenticationPrincipal CustomUserDetails customUserDetails,
                                 HttpServletResponse response) {
        return springFileDownloadService.getFileResource(objectKey, customUserDetails.getMember().getUserId(), response);
    }
}
