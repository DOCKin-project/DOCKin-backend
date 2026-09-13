-- ###########################################################################
-- ## 이 파일은 STALE 이다. 실행되지 않으며, 인용하면 안 된다.               ##
-- ###########################################################################
--
-- 1. 실행되지 않는다
--    application.properties 의 spring.sql.init.mode=never 때문이다.
--    테이블은 Hibernate 가 JPA 엔티티에서 만든다.
--
-- 2. 문법이 MySQL 이다
--    ENGINE=InnoDB / AUTO_INCREMENT / VARBINARY 등이 남아 있다.
--    2026-08-04 PostgreSQL 로 이관했으므로 그대로는 돌지도 않는다.
--
-- 3. 내용도 현재와 다르다
--    이관 이후 추가된 테이블(document_chunks, work_calendar 등)이 없다.
--
-- 현재 스키마를 보려면:  docs/db/postgresql-schema.sql
-- 엔티티와 DB 의 일치 검증:  SchemaValidationTest
--
-- 지우지 않고 남겨둔 이유는 MySQL 시절 설계 의도(주석)가 담겨 있어서다.
-- 참고 자료로만 쓴다.
-- ###########################################################################

-- 사용자
-- 사용자 권한
-- 장비 정보
-- 작업 일지
-- 체크리스트
-- 근태 관리
-- 채팅방
-- 긴급 연락처
-- 안전 관리
-- refresh_token
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS work_calendar;
DROP TABLE IF EXISTS document_chunks;
DROP TABLE IF EXISTS work_log_translations;
DROP TABLE IF EXISTS chat_history;
DROP TABLE IF EXISTS refresh_token;
DROP TABLE IF EXISTS safety_enrollments;
DROP TABLE IF EXISTS safety_courses;
DROP TABLE IF EXISTS chat_messages;
DROP TABLE IF EXISTS chat_members;
DROP TABLE IF EXISTS chat_rooms;
DROP TABLE IF EXISTS absence_requests;
DROP TABLE IF EXISTS attendance;
DROP TABLE IF EXISTS checklist_results;
DROP TABLE IF EXISTS checklist_items;
DROP TABLE IF EXISTS checklists;
DROP TABLE IF EXISTS work_log_views;
DROP TABLE IF EXISTS work_log_comments;
DROP TABLE IF EXISTS work_log_images;
DROP TABLE IF EXISTS work_logs;
DROP TABLE IF EXISTS equipment;
DROP TABLE IF EXISTS Authority;
DROP TABLE IF EXISTS users;

SET FOREIGN_KEY_CHECKS = 1;
-- 1. 사용자
CREATE TABLE users (
                       user_id VARCHAR(50) PRIMARY KEY,
                       name VARCHAR(10) NOT NULL,
                       password VARCHAR(256) NOT NULL,
                       role ENUM('ADMIN','USER'),
                       work_shift ENUM('MORNING', 'AFTERNOON', 'NIGHT') DEFAULT 'MORNING',
                       remaining_leave_days INT DEFAULT 15, -- 연차 정책 확정 전까지의 잠정 기본값
                       language_code VARCHAR(10) DEFAULT 'ko',
                       tts_enabled BOOLEAN DEFAULT TRUE,
                       created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                       updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                       ship_yard_area VARCHAR(100) NOT NULL
);

-- 2. 사용자 권한
CREATE TABLE Authority(
                          id INTEGER AUTO_INCREMENT PRIMARY KEY,
                          authority VARCHAR(256) NOT NULL,
                          user_id VARCHAR(50) NOT NULL,
                          FOREIGN KEY(user_id) REFERENCES users(user_id)
);

-- 3. 장비 정보
CREATE TABLE equipment (
                           equipment_id INT PRIMARY KEY AUTO_INCREMENT,
                           name VARCHAR(100),
                           nfc_tag VARCHAR(100) UNIQUE,
                           qr_code VARCHAR(100) UNIQUE,
                           created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                           updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 4. 작업 일지 (image_url 컬럼 제거)
CREATE TABLE work_logs (
                           log_id INT PRIMARY KEY AUTO_INCREMENT,
                           user_id VARCHAR(50),
                           title VARCHAR(256) NOT NULL,
                           equipment_id INT,
                           log_text TEXT NOT NULL,
                           audio_file_url VARCHAR(255),
                           created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                           updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                           FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
                           FOREIGN KEY (equipment_id) REFERENCES equipment(equipment_id)
);

-- 4-1. 작업 일지 이미지 (사진 여러 장 저장을 위해 새로 추가)
CREATE TABLE work_log_images (
                                 id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                 image_url VARCHAR(500) NOT NULL,
                                 work_log_id INT NOT NULL,
                                 FOREIGN KEY (work_log_id) REFERENCES work_logs(log_id) ON DELETE CASCADE
);

-- 5. 작업 일지 댓글
-- 현장 파트장이나 동료가 작업 일지에 피드백이나 지시사항을 남길 때 사용
CREATE TABLE work_log_comments (
                                   comment_id INT PRIMARY KEY AUTO_INCREMENT,
                                   log_id INT NOT NULL,               -- 어떤 일지의 댓글인지
                                   user_id VARCHAR(50) NOT NULL,      -- 작성자
                                   content TEXT NOT NULL,             -- 댓글 내용
                                   created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                                   updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                   FOREIGN KEY (log_id) REFERENCES work_logs(log_id) ON DELETE CASCADE,
                                   FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

-- 6. 작업 일지 열람 기록
-- 조선소 현장에서 누가 이 일지를 확인했는지 체크
CREATE TABLE work_log_views (
                                view_id INT PRIMARY KEY AUTO_INCREMENT,
                                log_id INT NOT NULL,               -- 읽은 일지 ID
                                user_id VARCHAR(50) NOT NULL,      -- 읽은 사람 ID
                                viewed_at DATETIME DEFAULT CURRENT_TIMESTAMP, -- 읽은 시간
                                FOREIGN KEY (log_id) REFERENCES work_logs(log_id) ON DELETE CASCADE,
                                FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    -- 동일인이 한 일지를 여러 번 읽어도 기록은 하나만 남도록 유니크 설정
                                UNIQUE KEY uk_user_log_view (log_id, user_id)
);

-- 7. 체크리스트 (템플릿)
CREATE TABLE checklists (
                            checklist_id INT PRIMARY KEY AUTO_INCREMENT,
                            equipment_id BIGINT NOT NULL,   -- Equipment 엔티티가 Long이라 BIGINT (기존 INT 표기는 부정확했음)
                            title VARCHAR(100) NOT NULL,
                            phase VARCHAR(10) NOT NULL,     -- 'PRE'/'POST'. 구 컬럼명 role -> phase (Member.role과 의미 충돌 방지)
                            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                            UNIQUE KEY uk_checklist_equipment_phase (equipment_id, phase),
                            FOREIGN KEY (equipment_id) REFERENCES equipment(equipment_id)
);

-- 8. 체크리스트 항목
CREATE TABLE checklist_items (
                                 item_id INT PRIMARY KEY AUTO_INCREMENT,
                                 checklist_id INT NOT NULL,
                                 content VARCHAR(255) NOT NULL,
                                 sequence INT NOT NULL,   -- 출력 순서 관리
                                 FOREIGN KEY (checklist_id) REFERENCES checklists(checklist_id)
);

-- 9. 체크리스트 결과 (append-only 감사 로그 - 체크/해제할 때마다 새 행 INSERT, upsert 없음)
CREATE TABLE checklist_results (
                                   result_id INT PRIMARY KEY AUTO_INCREMENT,
                                   checklist_item_id INT NOT NULL,   -- 구 checklist_id 대신 항목 단위로 변경 (통짜 is_checked로는 부분 완료를 표현 못 했음)
                                   user_id VARCHAR(50) NOT NULL,
                                   is_checked BOOLEAN NOT NULL,
                                   checked_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                                   FOREIGN KEY (checklist_item_id) REFERENCES checklist_items(item_id),
                                   FOREIGN KEY (user_id) REFERENCES users(user_id)
                                   -- equipment_id 컬럼 제거: checklist_item -> checklist -> equipment로 유도 가능한 중복 데이터였음
);


-- 10. 근태 관리 테이블
CREATE TABLE attendance (
                            id BIGINT PRIMARY KEY AUTO_INCREMENT,
                            user_id VARCHAR(50) NOT NULL,
                            clock_in_time DATETIME NOT NULL,
                            clock_out_time DATETIME,
                            work_date DATE NOT NULL,
                            role ENUM('NORMAL','LATE','ABSENT','VACATION','SICK'),
                            total_work_time VARCHAR(20) DEFAULT NULL,
                            in_location VARCHAR(255),
                            out_location VARCHAR(255),
                            CONSTRAINT fk_attendance_member FOREIGN KEY (user_id) REFERENCES users (user_id),
                            INDEX idx_work_date (work_date),
                            UNIQUE KEY uk_attendance_user_workdate (user_id, work_date)
);

-- 11. 근태 요청 (병결/휴가 서류 등록)
CREATE TABLE absence_requests (
                                  request_id INT PRIMARY KEY AUTO_INCREMENT,
                                  user_id VARCHAR(50) NOT NULL,
                                  request_type VARCHAR(20) NOT NULL, -- 'SICK'(병결), 'VACATION'(휴가)
                                  start_date DATE NOT NULL,
                                  end_date DATE NOT NULL,
                                  reason TEXT NOT NULL,
                                  document_url VARCHAR(255), -- 서류 파일 (이미지/PDF) 저장 경로 (S3 등)
                                  status VARCHAR(20) DEFAULT 'PENDING', -- 'PENDING'(대기), 'APPROVED'(승인), 'REJECTED'(거절)
                                  requested_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                                  processed_by VARCHAR(50), -- 승인/거절 처리한 관리자
                                  processed_at DATETIME,
                                  decision_comment TEXT, -- 승인/거절 사유 코멘트 (구 last_message_content; last_message_at은 processed_at과 중복이라 제거)
                                  FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
                                  FOREIGN KEY (processed_by) REFERENCES users(user_id)
);

-- 12. 채팅방 정보
CREATE TABLE chat_rooms (
                            room_id INT PRIMARY KEY AUTO_INCREMENT,
                            room_name VARCHAR(100), -- 단체방 이름 (1:1 방은 NULL 가능)
                            is_group BOOLEAN DEFAULT FALSE, -- 단체방 여부 (TRUE: 단체, FALSE: 1:1),
                            creator_id VARCHAR(50),
                            last_message_content TEXT,
                            last_message_at DATETIME,
                            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 13. 채팅방 참여자
CREATE TABLE chat_members (
                              id INT PRIMARY KEY AUTO_INCREMENT,
                              room_id INT NOT NULL,
                              user_id VARCHAR(50) NOT NULL,
                              joined_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                              last_read_time DATETIME DEFAULT CURRENT_TIMESTAMP, -- 마지막 읽은 시간 (안읽은 메시지 수 계산용)
                              FOREIGN KEY (room_id) REFERENCES chat_rooms(room_id) ON DELETE CASCADE,
                              FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

-- 14. 채팅 메시지
CREATE TABLE chat_messages (
                               message_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                               room_id INT NOT NULL,
                               sender_id VARCHAR(50) NOT NULL,
                               content TEXT NOT NULL,
                               message_type ENUM('TEXT','IMAGE','FILE') DEFAULT 'TEXT',
                               file_url VARCHAR(255),
                               sent_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                               FOREIGN KEY (room_id) REFERENCES chat_rooms(room_id) ON DELETE CASCADE,
                               INDEX idx_room_sent (room_id,sent_at)
);



-- 15. 안전 교육 과정 정보
CREATE TABLE safety_courses (
                                course_id INT PRIMARY KEY AUTO_INCREMENT,
                                title VARCHAR(255) NOT NULL,
                                description TEXT NOT NULL,
                                video_url VARCHAR(255) NOT NULL,
                                duration_minutes INT DEFAULT 0,

                                is_mandatory BOOLEAN DEFAULT TRUE, -- 필수 이수 여부
                                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 16. 사용자별 안전 교육 이수 상태
CREATE TABLE safety_enrollments (
                                    enrollment_id INT PRIMARY KEY AUTO_INCREMENT,
                                    user_id VARCHAR(50) NOT NULL,
                                    course_id INT NOT NULL,
                                    status VARCHAR(20) NOT NULL DEFAULT 'UNWATCHED',
                                    completion_date DATETIME, -- 이수 완료 시각 (NULL이면 미이수)
                                    enrolled_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                                    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
                                    FOREIGN KEY (course_id) REFERENCES safety_courses(course_id) ON DELETE CASCADE,
                                    UNIQUE KEY uk_user_course (user_id, course_id)
);

-- 17. refresh_token
CREATE TABLE refresh_token (
                               user_id VARCHAR(255) NOT NULL,
                               token VARCHAR(512) NOT NULL, -- JWT는 길기 때문에 길이를 충분히 줍니다.
                               PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 18. 챗봇
CREATE TABLE chat_history (
                              id BIGINT AUTO_INCREMENT PRIMARY KEY,
                              user_id VARCHAR(50),                  -- 어떤 사용자가 질문했는지 (추가 권장)
                              trace_id VARCHAR(255),                -- API 추적용 ID
                              user_query TEXT NOT NULL,             -- 사용자의 질문 내용 (추가 필수)
                              reply TEXT,                           -- 챗봇의 답변 내용

    -- RAG 감사 추적. 잘못된 답변이 나왔을 때 모델 문제인지 검색 문제인지 가르는 단서다.
    -- 규정 근거를 다루는 도메인에서는 "무엇을 근거로 답했는가"가 요구사항 자체가 된다.
                              source_chunk_ids TEXT,                -- 근거로 쓴 document_chunks.chunk_id 목록(쉼표 구분)
                              retrieval_mode VARCHAR(16),           -- VECTOR(정상) | KEYWORD(임베딩 장애 폴백) | NONE(근거 없음)

                              created_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
                              FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

-- 19. 번역된 작업 일지 테이블
-- TranslateLog 엔티티가 매핑되는 테이블. 엔티티는 원래 translate_logs를 가리키고 있었으나
-- 이 파일 및 ADR-0003/0006과 어긋나 있어 work_log_translations로 통일했다(RAG 교차언어 색인의 선결 과제).
--
-- uk_log_lang: 로그당 언어당 번역은 하나다. 제약이 없으면 재번역 시 중복 행이 쌓이고,
-- RAG 색인에서 같은 문서가 여러 번 색인되어 검색 결과를 오염시킨다.
-- 재번역은 새 행이 아니라 기존 행 갱신으로 처리한다(fastApiService.saveTranslateLog).
CREATE TABLE work_log_translations (
                                       translation_id INT PRIMARY KEY AUTO_INCREMENT,
                                       log_id INT NOT NULL,
                                       language_code VARCHAR(10) NOT NULL,  -- 구 targetLang
                                       user_id VARCHAR(50),                 -- 번역을 요청한 사용자
                                       trace_id VARCHAR(255),               -- FastAPI 호출 추적용
                                       original_title TEXT,                 -- 번역 시점의 원문. 원문이 수정돼도
                                       original_text TEXT,                  -- 어느 버전을 옮긴 것인지 추적 가능
                                       translated_title TEXT,
                                       translated_text TEXT,
                                       created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                                       updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                       FOREIGN KEY (log_id) REFERENCES work_logs(log_id) ON DELETE CASCADE,
                                       UNIQUE KEY uk_log_lang (log_id, language_code)
);

-- 20. 근무일 캘린더
-- 이 테이블이 없으면 결근 배치가 공휴일에 전원을 결근 처리한다.
-- WorkShift는 교대 시간대만 정의하고 휴무일 정보가 어디에도 없었다.
--
-- 날짜를 PK로 쓰는 자연키 설계다. 같은 날이 두 번 등록될 수 없어야 하는데,
-- 대리키 + 유니크 제약보다 자연키가 의도를 직접 드러낸다.
--
-- 등록되지 않은 날은 기본 규칙(평일=근무, 주말=휴무)을 따른다. 비워둬도 기존 동작이 유지되므로
-- 점진적으로 채울 수 있다. 반대로 "미등록=휴무"로 잡으면 캘린더를 채우기 전까지
-- 결근 배치가 조용히 무력화된다.
--
-- 범위: 전사 공통 휴무일만 다룬다. 교대조별 휴무 패턴은 근무 정책 엔진의 영역이다(백로그 P3).
CREATE TABLE work_calendar (
                               calendar_date DATE PRIMARY KEY,
                               day_type VARCHAR(20) NOT NULL, -- WORKDAY | WEEKEND | HOLIDAY | COMPANY_HOLIDAY
                               description VARCHAR(100)       -- 예: 광복절, 창립기념일, 토요 특근
);

-- 21. RAG 문서 청크 (벡터 검색용)
-- 설계 근거는 docs/SERVICE-SCALE-ASSUMPTIONS.md 3-2, docs/WORK-BACKLOG.md P1 참고.
--
-- FK를 걸지 않은 이유: work_logs / work_log_translations / safety_courses 등 여러 원본 테이블을
-- (source_type, source_id)로 다형 참조하기 때문에 단일 FK로 표현할 수 없다.
-- 원본 삭제 시 정리는 애플리케이션(IndexingService) 책임이며, idx_chunk_source가 그 조회를 받는다.
CREATE TABLE document_chunks (
                                 chunk_id BIGINT PRIMARY KEY AUTO_INCREMENT,

    -- 원본 식별 (다형 참조)
                                 source_type VARCHAR(32) NOT NULL,  -- WORK_LOG | WORK_LOG_TRANSLATION | SAFETY_COURSE | CHECKLIST_ITEM
                                 source_id BIGINT NOT NULL,
                                 chunk_index INT NOT NULL DEFAULT 0, -- 한 문서를 여러 청크로 나눴을 때의 순번

                                 language_code VARCHAR(10),          -- 교차언어 검색 평가에 사용 (ko/vi/en)
                                 content TEXT NOT NULL,              -- 청크 원문. 챗봇 프롬프트에 근거로 주입된다

    -- 멱등 재색인용. 원본 내용이 그대로면 임베딩을 다시 만들지 않고 건너뛴다.
    -- 인덱싱 배치가 중간에 끊겨도 이어서 돌릴 수 있어야 하므로 필수다(10만 청크 기준 약 20분 소요).
                                 content_hash CHAR(64) NOT NULL,     -- SHA-256 hex

    -- pgvector의 vector 타입. 현재 모델(multilingual-e5-small)이 384차원이다.
    -- MySQL VARBINARY(4096) -> PostgreSQL BYTEA -> vector(384) 순으로 바뀌어 왔다.
    -- 앞의 둘은 DB가 보기엔 바이트 뭉치라 유사도 계산을 애플리케이션에서 할 수밖에 없었다.
    --
    -- 차원을 384로 못박은 것은 HNSW 인덱스가 고정 차원 컬럼에만 걸리기 때문이다.
    -- 그 대가로 차원이 다른 모델의 청크가 공존할 수 없다(BYTEA 시절에는 가능했다).
    -- 확장 등록이 선행되어야 한다: CREATE EXTENSION IF NOT EXISTS vector;
                                 embedding vector(384) NOT NULL,
                                 embedding_dim INT NOT NULL,           -- 항상 384. 어느 차원 모델로 만든 행인지의 기록
                                 embedding_model VARCHAR(64) NOT NULL, -- 예: intfloat/multilingual-e5-small

    -- 권한 인지 검색용 비정규화 컬럼.
    -- 브루트포스는 전체 스캔이라 매 검색마다 원본 테이블을 조인하면 비용이 커진다.
    -- PUBLIC(안전교육/규정 등 전체 공개) / OWNER(작성자와 ADMIN만 조회 가능)
                                 visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
                                 owner_user_id VARCHAR(50) NULL,     -- visibility=OWNER일 때만 채운다

                                 created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                                 updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- 같은 원본/청크/모델 조합은 하나만 존재한다. 재색인 시 UPSERT 기준 키.
                                 UNIQUE KEY uk_chunk_source (source_type, source_id, chunk_index, embedding_model),
                                 INDEX idx_chunk_source (source_type, source_id),   -- 원본 삭제 시 청크 정리
                                 INDEX idx_chunk_model (embedding_model),           -- 모델 교체 시 구버전 일괄 삭제
                                 INDEX idx_chunk_visibility (visibility, owner_user_id) -- 권한 선필터
);