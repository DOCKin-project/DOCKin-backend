package com.DOCKin.global.error;

import lombok.Getter;

@Getter
public enum ErrorCode {
    // Common
    INVALID_INPUT_VALUE(400, "C001", "올바르지 않은 입력값입니다."),
    METHOD_NOT_ALLOWED(405, "C002", "허용되지 않은 HTTP 메서드입니다."),
    INTERNAL_SERVER_ERROR(500, "C003", "서버 내부 오류가 발생했습니다."),
    INVALID_TYPE_VALUE(400, "C004", "입력값의 타입이 적절하지 않습니다."),
    RESOURCE_NOT_FOUND(404, "C005", "요청한 경로를 찾을 수 없습니다."),
    UNSUPPORTED_MEDIA_TYPE(415, "C006", "지원하지 않는 Content-Type입니다."),
    PAYLOAD_TOO_LARGE(413, "C007", "업로드 가능한 최대 크기를 초과했습니다."),

    // Auth
    UNAUTHORIZED(401, "A001", "로그인이 필요한 서비스입니다."),
    ACCESS_DENIED(403, "A002", "해당 리소스에 대한 접근 권한이 없습니다."),
    TOKEN_EXPIRED(401, "A003", "인증 토큰이 만료되었습니다."),
    INVALID_TOKEN(401, "A004", "잘못된 인증 토큰입니다."),

    // User
    USER_NOT_FOUND(404, "U001", "존재하지 않는 사용자입니다."),
    USERID_DUPLICATION(400, "U002", "이미 가입된 사원번호입니다."),
    LOGIN_INPUT_INVALID(400, "U003", "사원번호 또는 비밀번호가 일치하지 않습니다."),
    EQUIPMENT_NOT_FOUND(400, "U004", "존재하지 않는 장비 번호입니다."),

   // Chat
    CHATROOM_NOT_FOUND(400, "CT001", "존재하지 않는 채팅입니다."),
    CHATMEMBER_NOT_FOUND(400, "CT002", "존재하지 않는 채팅방 멤버입니다."),
    CHATROOM_AUTHOR(400, "CT003", "채팅방의 권한이 없습니다."),

    //SafetyCourse
    SAFETYCOURSE_NOT_FOUND(404, "S001", "존재하지 않는 안전교육입니다."),
    SAFETYCOURSE_AUTHOR(403, "S002", "안전교육 수정 권한이 없습니다."),


    // Attendance
    ATTENDANCE_ALREADY_CHECKED(409, "AT001", "이미 오늘 출근 처리가 완료되었습니다."),
    ATTENDANCE_NOT_CHECKED_IN(404, "AT002", "오늘 출근 기록이 존재하지 않습니다."),
    ATTENDANCE_ALREADY_CHECKED_OUT(409, "AT003", "이미 오늘 퇴근 처리가 완료되었습니다."),

    // Checklist
    CHECKLIST_NOT_FOUND(404, "CK001", "존재하지 않는 체크리스트입니다."),
    CHECKLIST_ITEM_NOT_FOUND(404, "CK002", "존재하지 않는 체크리스트 항목입니다."),
    CHECKLIST_AUTHOR(403, "CK003", "체크리스트 관리 권한이 없습니다."),
    CHECKLIST_ALREADY_EXISTS(409, "CK004", "해당 장비와 단계에 대한 체크리스트가 이미 존재합니다."),
    CHECKLIST_ITEM_MISMATCH(400, "CK005", "해당 체크리스트에 속하지 않는 항목입니다."),
    CHECKLIST_HAS_RESULTS(409, "CK006", "이미 점검 기록이 있어 삭제할 수 없습니다."),
    CHECKLIST_ITEM_HAS_RESULTS(409, "CK007", "이미 점검 기록이 있어 항목을 삭제할 수 없습니다."),

    // Absence Request
    ABSENCE_REQUEST_NOT_FOUND(404, "AB001", "존재하지 않는 휴가 신청입니다."),
    ABSENCE_REQUEST_ALREADY_PROCESSED(409, "AB002", "이미 처리된 휴가 신청입니다."),
    ABSENCE_REQUEST_AUTHOR(403, "AB003", "휴가 신청 처리 권한이 없습니다."),
    INSUFFICIENT_LEAVE_DAYS(409, "AB004", "잔여 연차가 부족합니다."),
    INVALID_DATE_RANGE(400, "AB005", "종료일이 시작일보다 빠를 수 없습니다."),

    // Worklog
    LOG_NOT_FOUND(404, "W001", "존재하지 않는 작업 일지입니다."),
    NOT_LOG_AUTHOR(403, "W002", "해당 일지의 작성자가 아닙니다."),
    NOT_COMMENT_AUTHOR(403, "W003", "해당 댓글의 권한이 아닙니다."),
    COMMENT_NOT_FOUND(404, "W004", "존재하지 않는 댓글입니다."),

    // FastApi
    CHATBOT_NOT_WORK(404, "F001", "챗봇이 에러가 발생했습니다."),
    STT_CONVERSION_ERROR(404, "F002", "STT에서 에러가 발생했습니다."),

    // RAG
    EMBEDDING_SERVER_ERROR(503, "R001", "임베딩 서버 호출에 실패했습니다."),
    EMBEDDING_DIMENSION_MISMATCH(500, "R002", "임베딩 차원이 일치하지 않습니다.");


    private final int status;
    private final String code;
    private final String message;

    ErrorCode(int status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}