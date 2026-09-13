package com.DOCKin.global.file;

import java.util.Arrays;
import java.util.Optional;

/**
 * 업로드를 받아 주는 파일 종류. <b>내용의 첫 바이트로 판정</b>하고, 확장자와 Content-Type은 여기서 나온다.
 *
 * <h3>왜 파일명과 헤더를 믿지 않는가 (백로그 P2-18-9)</h3>
 * 이전에는 확장자를 원본 파일명에서 잘라 쓰고 Content-Type을 클라이언트 값 그대로 S3에 넣었다.
 * {@code .svg}나 {@code .html}을 올리면 버킷 도메인에서 스크립트가 도는 파일이 되고,
 * 점 없는 파일명이면 {@code substring}이 터져 500이었다. 파일명도 헤더도 보낸 쪽이 정하는 값이다.
 * 정하는 쪽이 아니라 <b>내용</b>을 본다.
 *
 * <p>목록은 이 서비스가 실제로 받는 것만이다 — 작업일지 사진(JPEG·PNG·WebP·GIF)과 휴가 증빙(PDF).
 * 늘릴 때는 "브라우저가 열면 실행되는가"를 먼저 묻는다. SVG는 그래서 없다.
 */
public enum UploadedFileType {
    JPEG("jpg", "image/jpeg", new int[]{0xFF, 0xD8, 0xFF}),
    PNG("png", "image/png", new int[]{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}),
    GIF("gif", "image/gif", new int[]{'G', 'I', 'F', '8'}),
    /** RIFF....WEBP — 4~7바이트는 파일 길이라 건너뛴다. */
    WEBP("webp", "image/webp", new int[]{'R', 'I', 'F', 'F', -1, -1, -1, -1, 'W', 'E', 'B', 'P'}),
    PDF("pdf", "application/pdf", new int[]{'%', 'P', 'D', 'F', '-'});

    /** 판정에 필요한 최대 바이트 수. 호출자는 이만큼만 읽으면 된다. */
    public static final int SNIFF_LENGTH = Arrays.stream(values())
            .mapToInt(t -> t.magic.length).max().orElse(0);

    private final String extension;
    private final String contentType;
    /** -1은 "아무 값이나". */
    private final int[] magic;

    UploadedFileType(String extension, String contentType, int[] magic) {
        this.extension = extension;
        this.contentType = contentType;
        this.magic = magic;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** 첫 바이트들로 종류를 가린다. 목록에 없으면 비어 있다 — 그 파일은 받지 않는다. */
    public static Optional<UploadedFileType> sniff(byte[] head) {
        for (UploadedFileType type : values()) {
            if (type.matches(head)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    private boolean matches(byte[] head) {
        if (head.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (magic[i] != -1 && (head[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
