--
-- 로컬 개발용 표본 데이터 (백로그 P0-13-4)
--
-- ============================================================================
-- 이 파일은 기본 Flyway location(classpath:db/migration)에 있지 않다.
-- 그래서 `seed` 프로파일이 spring.flyway.locations에 classpath:db/seed를
-- 명시적으로 추가하지 않는 한 Flyway가 이 디렉터리를 스캔조차 하지 않는다.
--
-- 실행:  SPRING_PROFILES_ACTIVE=seed ./gradlew bootRun
--
-- 설정을 건드리는 것이 아니라 "location을 추가해야 비로소 보인다"는 구조라
-- 잊고 배포해도 운영에 들어가지 않는다. 이전 data.sql은 반대였다 --
-- 기본 경로에 놓여 있고 스위치 하나(init.mode)로만 막혀 있었다.
-- ============================================================================
--
-- 반복 실행 가능(R__ = repeatable). 모든 INSERT가 ON CONFLICT DO NOTHING이라
-- 몇 번을 돌려도 결과가 같다. 내용을 고치면 체크섬이 바뀌어 다음 기동에 다시 적용된다.
--
-- document_chunks는 여기서 넣지 않는다. 임베딩이 필요하고 그것은 IndexingService의
-- 일이다 -- 손으로 vector(384) 리터럴을 적는 것은 가능하지도, 의미가 있지도 않다.
-- 이 시드가 만드는 것은 "색인할 원본"이고, 색인은 앱이 한다.
--

-- ---------------------------------------------------------------------------
-- 1. 사용자
--
-- 비밀번호는 넷 다 dockin1234 이고, 값은 BCryptPasswordEncoder로 실제 생성한
-- 해시다(SeedDataTest가 매 빌드마다 matches를 확인한다 -- 해시를 손으로 고치면
-- 로그인만 조용히 안 되는 종류라서).
--
-- worker02는 언어가 vi다. 교차언어 검색의 표본이 되라고 일부러 넣었다.
-- ---------------------------------------------------------------------------
INSERT INTO users (user_id, name, password, role, language_code, tts_enabled,
                   created_at, ship_yard_area, work_shift, remaining_leave_days) VALUES
  ('admin01',  '관리자',  '$2a$10$PiimjtEaM74GXkb9cTBvH.QZQSVSsMpwskriTfMIqP2ORzdCKgyLm', 'ADMIN', 'ko', false, TIMESTAMP '2026-01-02 09:00:00', '본사 안전관리팀', 'MORNING',   15),
  ('worker01', '김철수',  '$2a$10$PiimjtEaM74GXkb9cTBvH.QZQSVSsMpwskriTfMIqP2ORzdCKgyLm', 'USER',  'ko', false, TIMESTAMP '2026-01-05 09:00:00', '1도크 선각공장',   'MORNING',   12),
  ('worker02', '응웬반',  '$2a$10$PiimjtEaM74GXkb9cTBvH.QZQSVSsMpwskriTfMIqP2ORzdCKgyLm', 'USER',  'vi', true,  TIMESTAMP '2026-01-05 09:00:00', '2도크 의장공장',   'AFTERNOON', 15),
  ('worker03', '이영희',  '$2a$10$PiimjtEaM74GXkb9cTBvH.QZQSVSsMpwskriTfMIqP2ORzdCKgyLm', 'USER',  'ko', false, TIMESTAMP '2026-02-01 09:00:00', '3도크 도장공장',   'NIGHT',      8)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. 장비
-- ---------------------------------------------------------------------------
INSERT INTO equipment (equipment_id, name, qr_code, nfc_tag) VALUES
  (1, 'CO2 용접기 3호기',    'QR-WELD-003',  'NFC-WELD-003'),
  (2, '겐트리 크레인 1호기', 'QR-CRANE-001', 'NFC-CRANE-001'),
  (3, '무인 도장설비 A',     'QR-PAINT-A',   'NFC-PAINT-A'),
  (4, '플라즈마 절단기 2호', 'QR-CUT-002',   'NFC-CUT-002'),
  (5, '고소작업대 5호',      'QR-LIFT-005',  'NFC-LIFT-005')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. 근무일 캘린더
--
-- 미등록 = 기본 규칙(평일 근무 / 주말 휴무)이므로 예외만 넣는다.
-- 마지막 행은 주말인데 일하는 날 -- DayType.WORKDAY를 토요일에 등록하면 특근일이 된다.
-- 이게 캘린더가 양방향 예외를 표현할 수 있는지 확인하는 표본이다.
-- ---------------------------------------------------------------------------
INSERT INTO work_calendar (calendar_date, day_type, description) VALUES
  (DATE '2026-08-15', 'HOLIDAY',         '광복절'),
  (DATE '2026-09-24', 'HOLIDAY',         '추석 연휴'),
  (DATE '2026-09-25', 'HOLIDAY',         '추석'),
  (DATE '2026-09-26', 'HOLIDAY',         '추석 연휴'),
  (DATE '2026-08-14', 'COMPANY_HOLIDAY', '창립기념일'),
  (DATE '2026-08-22', 'WORKDAY',         '납기 대응 특근(토)')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. 안전교육
--
-- Visibility.PUBLIC으로 색인되므로 권한 필터 없이 누구에게나 검색된다.
-- 작업일지(OWNER)와 섞어두어야 선필터가 실제로 갈라내는지 눈으로 볼 수 있다.
-- ---------------------------------------------------------------------------
INSERT INTO safety_courses (course_id, title, description, video_url, material_url,
                            duration_minutes, created_by, created_at) VALUES
  (1, '밀폐공간 작업 안전수칙',
   '선박 내부의 탱크, 이중저, 코퍼댐은 모두 밀폐공간에 해당한다. 진입 전 반드시 산소 농도를 측정하고 18% 이상 23.5% 이하임을 확인해야 한다. 황화수소와 일산화탄소 농도도 함께 측정한다. 측정은 진입 직전에 다시 한 번 실시하며, 이전 교대조의 측정 결과를 신뢰해서는 안 된다. 작업 중에는 반드시 외부에 감시인을 배치하고 상시 연락 수단을 유지한다. 환기 장치는 작업 시작 30분 전부터 가동하며 작업 종료까지 멈추지 않는다.',
   'https://example.local/safety/confined-space.mp4', 'https://example.local/safety/confined-space.pdf', 25, 'admin01', TIMESTAMP '2026-03-02 10:00:00'),
  (2, '용접 및 화기작업 안전',
   '용접, 절단, 그라인딩은 모두 화기작업에 해당하며 작업 전 화기작업 허가서를 발급받아야 한다. 작업 반경 11미터 이내의 가연물은 제거하거나 불연성 덮개로 차단한다. 소화기는 작업 위치에서 손이 닿는 거리에 비치한다. 용접 흄은 밀폐공간에서 급격히 축적되므로 국소배기장치를 함께 사용한다. 작업 종료 후 최소 30분간 화재 감시를 유지해야 하며, 이 감시를 생략해 발생한 사고가 사내에서만 세 건 있었다.',
   'https://example.local/safety/hot-work.mp4', NULL, 30, 'admin01', TIMESTAMP '2026-03-02 10:10:00'),
  (3, '고소작업 추락 재해 예방',
   '2미터 이상의 높이에서 이루어지는 모든 작업은 고소작업이다. 안전대는 착용만으로 부족하며 반드시 구명줄이나 고정된 구조물에 체결해야 한다. 이동 중에도 체결이 끊기지 않도록 이중 후크를 사용한다. 작업발판은 폭 40센티미터 이상, 발판 사이 틈은 3센티미터 이하여야 한다. 강풍주의보가 발효되면 고소작업을 중단한다.',
   'https://example.local/safety/fall-prevention.mp4', 'https://example.local/safety/fall-prevention.pdf', 20, 'admin01', TIMESTAMP '2026-03-02 10:20:00'),
  (4, '크레인 및 중량물 취급',
   '크레인 인양 반경 아래로는 어떤 경우에도 통행할 수 없다. 신호수는 지정된 한 명만 두며, 여러 사람이 동시에 신호하면 운전자가 판단할 수 없다. 인양 전 와이어로프의 소선 절단 여부를 확인하고, 한 꼬임에서 소선이 10퍼센트 이상 절단되었으면 즉시 교체한다. 정격하중을 초과하는 인양은 어떤 사유로도 허용되지 않는다.',
   'https://example.local/safety/crane.mp4', NULL, 22, 'admin01', TIMESTAMP '2026-03-02 10:30:00'),
  (5, '도장작업 유기용제 중독 예방',
   '도장 및 시너 취급 구역은 유기용제 증기가 체류하므로 방독마스크를 착용한다. 방진마스크로는 유기용제를 걸러낼 수 없다. 정화통은 사용 시간을 기록하고 파과 시간이 지나면 교체한다. 도장 구역 반경 내에서는 화기 사용이 전면 금지되며, 정전기에 의한 착화를 막기 위해 접지를 확인한다.',
   'https://example.local/safety/solvent.mp4', NULL, 18, 'admin01', TIMESTAMP '2026-03-02 10:40:00')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 5. 작업일지
--
-- Visibility.OWNER로 색인된다. 작성자가 셋으로 갈려 있어야 권한 선필터가
-- 실제로 무언가를 걸러낸다 -- 한 사람 것만 있으면 필터가 있으나 마나다.
--
-- 길이를 일부러 섞었다. TARGET_CHARS가 500이므로 짧은 것은 1청크, log_id 3/9/14는
-- 900자를 넘겨 2~3청크가 된다. RetrievalService의 "원본 문서당 최대 2청크" 제한은
-- 한 문서가 여러 청크로 쪼개져야 비로소 동작하는 코드다.
-- ---------------------------------------------------------------------------
INSERT INTO work_logs (log_id, title, log_text, audio_file_url, created_at, updated_at, user_id, equipment_id) VALUES
  (1, 'CO2 용접기 3호기 와이어 송급 불량',
   '오전 작업 시작 직후부터 용접 와이어가 간헐적으로 멈추는 현상이 발생했다. 송급 롤러를 열어 확인하니 홈에 스패터가 눌어붙어 있었고 롤러 압력도 규정보다 낮게 설정되어 있었다. 스패터를 제거하고 압력을 재조정한 뒤 시험 비드를 다섯 차례 놓아 송급이 안정된 것을 확인했다. 다만 라이너 내부에도 분진이 상당히 쌓여 있어 다음 정기 점검 때 라이너 교체를 요청해 두었다.',
   NULL, TIMESTAMP '2026-07-06 08:40:00', TIMESTAMP '2026-07-06 08:40:00', 'worker01', 1),
  (2, '선각 블록 맞대기 용접부 육안검사',
   '3번 블록과 4번 블록 맞대기 이음부 전 구간을 육안검사했다. 언더컷이 두 군데 발견되어 깊이를 측정하니 각각 0.4밀리미터와 0.6밀리미터였다. 0.6밀리미터 부위는 허용 기준을 넘어 그라인딩 후 재용접했다. 나머지 구간은 비드 폭이 균일하고 결함이 없었다.',
   NULL, TIMESTAMP '2026-07-07 09:15:00', TIMESTAMP '2026-07-07 09:15:00', 'worker01', 1),
  (3, '밀폐공간 이중저 탱크 진입 작업 기록',
   '이중저 3번 탱크 내부 배관 보수를 위해 진입했다. 진입 전 산소 농도를 측정하니 19.8퍼센트로 정상 범위였고 황화수소는 검출되지 않았다. 환기 장치는 작업 시작 40분 전부터 가동했다. 감시인은 입구에 상주하며 15분 간격으로 무전 교신을 유지했다. 작업 중 오전 10시 20분경 내부 산소 농도가 19.1퍼센트까지 떨어져 일시 철수한 뒤 환기를 20분 추가로 실시했다. 재진입 시 20.2퍼센트로 회복된 것을 확인하고 작업을 재개했다. 이 일시 철수 판단은 규정상 18퍼센트 미만에서만 의무이지만, 하강 추세가 확인된 이상 기준값에 도달할 때까지 기다릴 이유가 없다고 보았다. 배관 보수 자체는 플랜지 개스킷 교체와 볼트 재체결로 마무리했고 누설 시험에서 이상이 없었다. 다음 교대조에 환기 장치를 끄지 말 것과, 산소 농도가 하강 추세를 보인 원인이 아직 규명되지 않았다는 점을 인계했다.',
   NULL, TIMESTAMP '2026-07-08 11:30:00', TIMESTAMP '2026-07-08 11:30:00', 'worker01', NULL),
  (4, '겐트리 크레인 1호기 와이어로프 점검',
   '정기 점검에서 주 권상 와이어로프의 소선 절단을 확인했다. 한 꼬임 구간에서 소선 네 가닥이 절단되어 있었고 전체 소선 수 대비 약 7퍼센트에 해당한다. 교체 기준인 10퍼센트에는 미달하지만 절단 위치가 집중되어 있어 교체를 건의했다. 킹크나 변형은 없었다.',
   NULL, TIMESTAMP '2026-07-09 07:50:00', TIMESTAMP '2026-07-09 07:50:00', 'worker01', 2),
  (5, '크레인 인양 중 신호 혼선 발생',
   '중량물 인양 중 신호수 외 다른 작업자가 손신호를 보내 운전자가 일시 정지했다. 인양물은 지상 1미터 높이에서 멈췄고 인명 피해나 물적 손상은 없었다. 작업을 중단하고 신호수를 한 명으로 재지정한 뒤 재개했다. 크레인 안전교육에 명시된 내용이 현장에서 지켜지지 않은 사례라 조회 시간에 공유를 요청했다.',
   NULL, TIMESTAMP '2026-07-10 13:20:00', TIMESTAMP '2026-07-10 13:20:00', 'worker01', 2),
  (6, '고소작업대 5호 유압 누유',
   '고소작업대 붐 실린더 하부에서 유압유가 배어 나오는 것을 발견했다. 바닥에 지름 15센티미터 정도의 유막이 형성되어 있었다. 실린더 로드 씰 손상으로 판단되어 장비를 사용 정지 처리하고 정비팀에 이관했다. 사용 정지 표지를 부착했다.',
   NULL, TIMESTAMP '2026-07-13 08:10:00', TIMESTAMP '2026-07-13 08:10:00', 'worker01', 5),

  (7, '의장품 취부 작업 진행',
   '2도크 의장공장에서 파이프 서포트 취부 작업을 진행했다. 도면 대비 취부 위치가 12밀리미터 어긋난 곳이 한 군데 있어 마킹을 다시 하고 재취부했다. 어긋난 원인은 이전 공정의 기준선 마킹 오류로 확인되어 선행 공정에 통보했다.',
   NULL, TIMESTAMP '2026-07-06 15:00:00', TIMESTAMP '2026-07-06 15:00:00', 'worker02', NULL),
  (8, '플라즈마 절단기 2호 절단면 품질 저하',
   '절단면에 드로스가 과다하게 발생하고 절단 폭이 일정하지 않았다. 노즐을 확인하니 오리피스가 마모되어 원형이 무너져 있었다. 노즐과 전극을 함께 교체한 뒤 시험 절단을 실시하니 절단면이 정상으로 돌아왔다. 노즐 사용 시간이 관리되지 않고 있어 교체 주기를 기록으로 남기자고 제안했다.',
   NULL, TIMESTAMP '2026-07-07 16:20:00', TIMESTAMP '2026-07-07 16:20:00', 'worker02', 4),
  (9, '의장 배관 수압시험 및 누설 보수 기록',
   '3번 구역 소화수 배관 계통 수압시험을 실시했다. 시험 압력은 설계 압력의 1.5배인 10.5바로 30분간 유지하는 조건이었다. 가압 후 8분 경과 시점에 압력이 10.5바에서 9.7바로 떨어지는 것이 확인되어 시험을 중단하고 누설 지점을 찾았다. 플랜지 이음부 세 곳에서 미세 누설이 있었고 그중 두 곳은 볼트 체결 토크 부족, 한 곳은 개스킷 압착 불량이었다. 토크 렌치로 규정 토크인 85뉴턴미터에 맞춰 재체결하고 개스킷을 교체한 뒤 재시험했다. 재시험에서는 30분간 압력 강하가 0.1바 이내로 기준을 충족했다. 체결 토크 부족이 두 곳에서 나온 것은 개인 편차가 아니라 토크 렌치가 현장에 한 대뿐이어서 순번을 기다리다 그냥 임팩트로 조인 것이 원인이었다. 공구 수량 문제이므로 반장에게 별도로 보고했다. 이 계통은 시운전 전에 한 번 더 확인이 필요하다.',
   NULL, TIMESTAMP '2026-07-09 14:40:00', TIMESTAMP '2026-07-09 14:40:00', 'worker02', NULL),
  (10, '용접 흄 배기 불량 개선 요청',
   '의장공장 내부 용접 구역의 국소배기장치 흡입력이 눈에 띄게 약했다. 덕트 연결부가 이탈되어 있었고 필터에 분진이 가득 차 있었다. 연결부를 재체결하고 필터를 교체하니 흡입이 정상으로 돌아왔다. 필터 교체 이력이 없어 언제부터 이 상태였는지 알 수 없다.',
   NULL, TIMESTAMP '2026-07-14 15:30:00', TIMESTAMP '2026-07-14 15:30:00', 'worker02', 1),
  (11, '안전화 미착용 작업자 적발 및 조치',
   '작업 구역 순회 중 안전화를 착용하지 않은 작업자 한 명을 확인했다. 당일 오전 안전화가 파손되어 교체 신청 중이었다고 진술했다. 예비 안전화를 지급하고 작업에 복귀시켰다. 보호구 예비 재고가 사이즈별로 갖춰져 있지 않아 지급까지 시간이 걸렸다.',
   NULL, TIMESTAMP '2026-07-15 14:10:00', TIMESTAMP '2026-07-15 14:10:00', 'worker02', NULL),

  (12, '무인 도장설비 A 노즐 막힘',
   '야간 도장 작업 중 스프레이 패턴이 한쪽으로 치우치는 현상이 발생했다. 노즐을 분해하니 도료가 굳어 유로를 부분적으로 막고 있었다. 시너로 세척하고 재조립한 뒤 시험 분사에서 패턴이 정상으로 회복되었다. 작업 종료 시 세척 절차가 지켜지지 않은 것으로 보인다.',
   NULL, TIMESTAMP '2026-07-07 23:30:00', TIMESTAMP '2026-07-07 23:30:00', 'worker03', 3),
  (13, '도장 구역 유기용제 농도 측정',
   '도장 부스 내부 유기용제 농도를 측정했다. 크실렌 농도가 노출 기준의 60퍼센트 수준으로 측정되어 기준 이내였다. 다만 부스 출입문을 열어둔 상태에서는 인접 통로에서도 냄새가 감지되어 출입문 상시 폐쇄를 요청했다. 방독마스크 정화통 사용 시간 기록표를 부스 입구에 부착했다.',
   NULL, TIMESTAMP '2026-07-08 22:50:00', TIMESTAMP '2026-07-08 22:50:00', 'worker03', 3),
  (14, '야간 도장 작업 중 정전 대응 기록',
   '새벽 2시 15분경 도장공장 일부 구역에 정전이 발생했다. 도장설비와 환기 장치가 동시에 정지했고 비상등만 점등된 상태였다. 도장 부스 내부에 작업자 두 명이 있었으며 환기가 멈춘 상태에서 유기용제 증기가 체류할 위험이 있어 즉시 대피시켰다. 대피는 정전 발생 후 약 90초 이내에 완료되었다. 전기팀 확인 결과 도장공장 배전반의 누전차단기가 동작한 것이었고, 원인은 옥외에 방치된 임시 배선의 절연 파괴였다. 강우로 물이 유입되면서 절연이 깨진 것으로 보인다. 임시 배선을 철거하고 차단기를 복구한 시각은 새벽 3시 40분이며, 복구 후 환기 장치를 30분 가동해 부스 내부 농도를 측정하고 정상을 확인한 뒤 작업을 재개했다. 이번 건에서 문제로 남는 것은 정전 시 환기 장치가 비상 전원 계통에 물려 있지 않다는 점이다. 대피가 빨라 사고로 이어지지 않았을 뿐, 부스 안쪽 깊은 곳에서 작업 중이었다면 상황이 달랐을 것이다. 환기 장치를 비상 전원에 포함시켜 달라고 정식으로 요청했다.',
   NULL, TIMESTAMP '2026-07-10 05:00:00', TIMESTAMP '2026-07-10 05:00:00', 'worker03', 3),
  (15, '도장 전 표면처리 상태 확인',
   '블라스팅 완료 구간의 표면 거칠기를 측정했다. 목표값은 40에서 70마이크로미터였고 측정값은 52마이크로미터로 범위 내였다. 염분 측정에서도 기준 이내였다. 다만 일부 모서리 구간에 블라스팅이 덜 된 부분이 남아 있어 재작업을 지시했다.',
   NULL, TIMESTAMP '2026-07-14 21:40:00', TIMESTAMP '2026-07-14 21:40:00', 'worker03', 3),
  (16, '야간 교대 인계 사항 정리',
   '야간 교대조 인계 사항을 정리했다. 도장설비 A는 정상 가동 중이며 노즐 세척은 완료했다. 3번 부스는 건조 대기 상태로 다음 조가 오전 6시 이후 도막 두께를 측정해야 한다. 임시 배선 철거 이후 옥외 전원 사용은 전기팀 승인 후에만 하기로 했다.',
   NULL, TIMESTAMP '2026-07-15 05:10:00', TIMESTAMP '2026-07-15 05:10:00', 'worker03', NULL)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 6. 작업일지 번역본 -- 교차언어 검색의 정답 라벨
--
-- IndexingService 주석대로 (log_id, language_code)가 곧 정답 라벨이다.
-- "베트남어 질의로 검색했을 때 log_id 7의 원문이 나와야 한다"를 지어내지 않고
-- 데이터에서 읽을 수 있게 하는 것이 이 표의 목적이다.
--
-- worker02(언어 vi)가 쓴 작업일지 다섯 건에 베트남어 번역본을, 그중 두 건에는
-- 영어 번역본도 넣었다. 같은 log_id에 언어가 둘이면 uk_log_lang이 실제로
-- 무엇을 막는지도 함께 드러난다.
-- ---------------------------------------------------------------------------
INSERT INTO work_log_translations (translation_id, log_id, user_id, trace_id, language_code,
                                   original_title, translated_title,
                                   original_text, translated_text,
                                   created_at, updated_at) VALUES
  (1, 7, 'worker02', 'seed-tr-0001', 'vi',
   '의장품 취부 작업 진행', 'Tiến hành công việc lắp đặt thiết bị',
   '2도크 의장공장에서 파이프 서포트 취부 작업을 진행했다. 도면 대비 취부 위치가 12밀리미터 어긋난 곳이 한 군데 있어 마킹을 다시 하고 재취부했다.',
   'Đã tiến hành công việc lắp giá đỡ ống tại xưởng lắp ráp ụ tàu số 2. Có một vị trí lắp lệch 12 mm so với bản vẽ nên đã đánh dấu lại và lắp lại.',
   TIMESTAMP '2026-07-06 15:30:00', TIMESTAMP '2026-07-06 15:30:00'),
  (2, 8, 'worker02', 'seed-tr-0002', 'vi',
   '플라즈마 절단기 2호 절단면 품질 저하', 'Chất lượng mặt cắt của máy cắt plasma số 2 giảm',
   '절단면에 드로스가 과다하게 발생하고 절단 폭이 일정하지 않았다. 노즐을 확인하니 오리피스가 마모되어 원형이 무너져 있었다. 노즐과 전극을 함께 교체했다.',
   'Xỉ bám quá nhiều trên mặt cắt và bề rộng vết cắt không đều. Kiểm tra vòi phun thì thấy lỗ phun đã mòn và mất hình tròn. Đã thay cả vòi phun và điện cực.',
   TIMESTAMP '2026-07-07 16:50:00', TIMESTAMP '2026-07-07 16:50:00'),
  (3, 9, 'worker02', 'seed-tr-0003', 'vi',
   '의장 배관 수압시험 및 누설 보수 기록', 'Ghi chép thử áp lực nước và sửa chữa rò rỉ đường ống',
   '소화수 배관 계통 수압시험에서 압력이 떨어져 누설 지점을 찾았다. 플랜지 이음부 세 곳에서 미세 누설이 있었고 재체결과 개스킷 교체 후 재시험을 통과했다.',
   'Trong thử áp lực hệ thống ống nước chữa cháy, áp suất bị tụt nên đã tìm điểm rò rỉ. Có rò rỉ nhỏ tại ba mối nối bích; sau khi siết lại và thay gioăng thì thử lại đạt yêu cầu.',
   TIMESTAMP '2026-07-09 15:10:00', TIMESTAMP '2026-07-09 15:10:00'),
  (4, 10, 'worker02', 'seed-tr-0004', 'vi',
   '용접 흄 배기 불량 개선 요청', 'Yêu cầu khắc phục hút khói hàn kém',
   '용접 구역 국소배기장치 흡입력이 약했다. 덕트 연결부가 이탈되고 필터에 분진이 가득 차 있어 재체결과 필터 교체 후 정상으로 돌아왔다.',
   'Lực hút của thiết bị hút cục bộ ở khu vực hàn rất yếu. Chỗ nối ống gió bị tuột và bộ lọc đầy bụi; sau khi nối lại và thay bộ lọc thì trở lại bình thường.',
   TIMESTAMP '2026-07-14 16:00:00', TIMESTAMP '2026-07-14 16:00:00'),
  (5, 11, 'worker02', 'seed-tr-0005', 'vi',
   '안전화 미착용 작업자 적발 및 조치', 'Phát hiện và xử lý công nhân không mang giày bảo hộ',
   '순회 중 안전화를 착용하지 않은 작업자를 확인했다. 예비 안전화를 지급하고 작업에 복귀시켰다.',
   'Trong lúc đi tuần đã phát hiện một công nhân không mang giày bảo hộ. Đã cấp giày bảo hộ dự phòng và cho quay lại làm việc.',
   TIMESTAMP '2026-07-15 14:40:00', TIMESTAMP '2026-07-15 14:40:00'),
  (6, 7, 'worker02', 'seed-tr-0006', 'en',
   '의장품 취부 작업 진행', 'Outfitting installation work in progress',
   '2도크 의장공장에서 파이프 서포트 취부 작업을 진행했다. 도면 대비 취부 위치가 12밀리미터 어긋난 곳이 한 군데 있어 재취부했다.',
   'Pipe support installation was carried out at the No. 2 dock outfitting shop. One support was installed 12 mm off from the drawing, so it was re-marked and reinstalled.',
   TIMESTAMP '2026-07-06 15:35:00', TIMESTAMP '2026-07-06 15:35:00'),
  (7, 9, 'worker02', 'seed-tr-0007', 'en',
   '의장 배관 수압시험 및 누설 보수 기록', 'Hydrostatic test and leak repair record for outfitting piping',
   '소화수 배관 계통 수압시험에서 압력이 떨어져 누설 지점을 찾았다. 플랜지 이음부 세 곳에서 미세 누설이 있었다.',
   'During the hydrostatic test of the fire water piping system the pressure dropped, so the leak points were located. Minor leaks were found at three flange joints.',
   TIMESTAMP '2026-07-09 15:20:00', TIMESTAMP '2026-07-09 15:20:00')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 7. 근태
--
-- attendance.id는 IDENTITY가 아니라 attendance_seq를 쓴다(Hibernate SEQUENCE 전략,
-- allocationSize=50). 그래서 아래 9절의 시퀀스 정렬 방식이 다른 표와 다르다.
--
-- work_date에 uk_attendance_user_workdate가 걸려 있어 하루 두 행이 들어가지 않는다.
-- 2026-07-13(월)의 worker03은 일부러 비워 뒀다 -- 결근 배치가 무엇을 대상으로
-- 삼는지 보려면 "기록이 없는 근무일"이 하나는 있어야 한다.
-- ---------------------------------------------------------------------------
INSERT INTO attendance (id, user_id, work_date, clock_in_time, clock_out_time,
                        in_location, out_location, status, total_work_time) VALUES
  (1,  'worker01', DATE '2026-07-06', TIMESTAMP '2026-07-06 05:52:00', TIMESTAMP '2026-07-06 15:04:00', '1도크 게이트', '1도크 게이트', 'NORMAL',     '9h12m'),
  (2,  'worker01', DATE '2026-07-07', TIMESTAMP '2026-07-07 05:58:00', TIMESTAMP '2026-07-07 15:01:00', '1도크 게이트', '1도크 게이트', 'NORMAL',     '9h03m'),
  (3,  'worker01', DATE '2026-07-08', TIMESTAMP '2026-07-08 06:21:00', TIMESTAMP '2026-07-08 15:10:00', '1도크 게이트', '1도크 게이트', 'LATE',       '8h49m'),
  (4,  'worker01', DATE '2026-07-09', TIMESTAMP '2026-07-09 05:49:00', TIMESTAMP '2026-07-09 15:02:00', '1도크 게이트', '1도크 게이트', 'NORMAL',     '9h13m'),
  (5,  'worker01', DATE '2026-07-10', TIMESTAMP '2026-07-10 05:55:00', TIMESTAMP '2026-07-10 12:30:00', '1도크 게이트', '1도크 게이트', 'LEFT_EARLY', '6h35m'),
  (6,  'worker01', DATE '2026-07-13', NULL, NULL, NULL, NULL, 'VACATION', NULL),

  (7,  'worker02', DATE '2026-07-06', TIMESTAMP '2026-07-06 13:55:00', TIMESTAMP '2026-07-06 23:02:00', '2도크 게이트', '2도크 게이트', 'NORMAL', '9h07m'),
  (8,  'worker02', DATE '2026-07-07', TIMESTAMP '2026-07-07 13:51:00', TIMESTAMP '2026-07-07 23:00:00', '2도크 게이트', '2도크 게이트', 'NORMAL', '9h09m'),
  (9,  'worker02', DATE '2026-07-08', NULL, NULL, NULL, NULL, 'ABSENT', NULL),
  (10, 'worker02', DATE '2026-07-09', TIMESTAMP '2026-07-09 13:58:00', TIMESTAMP '2026-07-09 23:05:00', '2도크 게이트', '2도크 게이트', 'NORMAL', '9h07m'),
  (11, 'worker02', DATE '2026-07-10', TIMESTAMP '2026-07-10 13:47:00', TIMESTAMP '2026-07-10 22:58:00', '2도크 게이트', '2도크 게이트', 'NORMAL', '9h11m'),

  (12, 'worker03', DATE '2026-07-06', TIMESTAMP '2026-07-06 21:50:00', TIMESTAMP '2026-07-07 07:02:00', '3도크 게이트', '3도크 게이트', 'NORMAL', '9h12m'),
  (13, 'worker03', DATE '2026-07-07', TIMESTAMP '2026-07-07 21:56:00', TIMESTAMP '2026-07-08 07:00:00', '3도크 게이트', '3도크 게이트', 'NORMAL', '9h04m'),
  (14, 'worker03', DATE '2026-07-08', TIMESTAMP '2026-07-08 21:48:00', TIMESTAMP '2026-07-09 07:05:00', '3도크 게이트', '3도크 게이트', 'NORMAL', '9h17m'),
  (15, 'worker03', DATE '2026-07-09', TIMESTAMP '2026-07-09 22:14:00', TIMESTAMP '2026-07-10 07:01:00', '3도크 게이트', '3도크 게이트', 'LATE',   '8h47m'),
  (16, 'worker03', DATE '2026-07-10', TIMESTAMP '2026-07-10 21:52:00', TIMESTAMP '2026-07-11 07:03:00', '3도크 게이트', '3도크 게이트', 'NORMAL', '9h11m')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 8. 휴가 신청
--
-- APPROVED 한 건은 위 attendance의 2026-07-13 VACATION 행과 짝이 맞다.
-- 승인만 있고 근태에 반영이 없으면 P2-1이 고친 상태와 어긋나는 데이터가 되어,
-- 그 자체가 틀린 표본이 된다.
--
-- worker01의 remaining_leave_days를 12로 둔 것도 같은 이유다 -- 기본값 15에서
-- 이 승인 건(3일)이 차감된 결과여야 앞뒤가 맞는다.
-- ---------------------------------------------------------------------------
INSERT INTO absence_requests (request_id, user_id, request_type, start_date, end_date,
                              reason, status, requested_at, processed_at, processed_by,
                              decision_comment, document_url) VALUES
  (1, 'worker01', 'VACATION', DATE '2026-07-13', DATE '2026-07-15',
   '가족 행사 참석', 'APPROVED', TIMESTAMP '2026-07-01 10:00:00', TIMESTAMP '2026-07-02 09:30:00',
   'admin01', '결재 완료', NULL),
  (2, 'worker02', 'SICK', DATE '2026-08-10', DATE '2026-08-11',
   '병원 진료', 'PENDING', TIMESTAMP '2026-08-03 17:20:00', NULL, NULL, NULL, NULL),
  (3, 'worker03', 'VACATION', DATE '2026-08-24', DATE '2026-08-24',
   '개인 사유', 'REJECTED', TIMESTAMP '2026-08-04 08:00:00', TIMESTAMP '2026-08-04 11:00:00',
   'admin01', '해당 일자 인원 부족', NULL)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 9. 시퀀스 정렬 -- 빠뜨리면 시드가 아니라 그다음 INSERT가 죽는다
--
-- 위에서 PK를 명시해 넣었는데, GENERATED BY DEFAULT AS IDENTITY는 명시된 값을
-- 받아주기만 하고 시퀀스를 따라 올리지 않는다. 그대로 두면 앱이 처음 작성하는
-- 작업일지가 log_id=1을 받아 PK 충돌로 실패한다.
--
-- 시드는 성공하고, 화면도 잘 뜨고, "글쓰기"를 누르는 순간 터진다.
-- 원인이 시드에 있다는 것을 그 시점에 떠올리기 어려운 종류라 여기서 막는다.
--
-- setval의 세 번째 인자를 생략하면 is_called=true이므로 다음 값은 MAX+1이다.
-- GREATEST/COALESCE는 표가 비어 있는 경우(=시드가 전부 충돌로 건너뛰어진 뒤
-- 누군가 데이터를 지운 경우) setval이 NULL을 받아 터지는 것을 막는다.
-- ---------------------------------------------------------------------------
-- users는 PK가 문자열(user_id)이라 시퀀스가 없다. 그래서 이 목록에 없다.
SELECT setval(pg_get_serial_sequence('equipment', 'equipment_id'),
              GREATEST(COALESCE((SELECT MAX(equipment_id) FROM equipment), 1), 1));
SELECT setval(pg_get_serial_sequence('safety_courses', 'course_id'),
              GREATEST(COALESCE((SELECT MAX(course_id) FROM safety_courses), 1), 1));
SELECT setval(pg_get_serial_sequence('work_logs', 'log_id'),
              GREATEST(COALESCE((SELECT MAX(log_id) FROM work_logs), 1), 1));
SELECT setval(pg_get_serial_sequence('work_log_translations', 'translation_id'),
              GREATEST(COALESCE((SELECT MAX(translation_id) FROM work_log_translations), 1), 1));
SELECT setval(pg_get_serial_sequence('absence_requests', 'request_id'),
              GREATEST(COALESCE((SELECT MAX(request_id) FROM absence_requests), 1), 1));

-- attendance는 IDENTITY가 아니라 attendance_seq(INCREMENT BY 50)를 쓴다.
-- Hibernate의 pooled 옵티마이저는 nextval이 돌려준 값 V에 대해 (V-50, V] 구간을
-- 쓰므로, 시드가 쓴 1~16보다 충분히 위에서 시작하게 1000으로 올려둔다.
-- 다음 nextval은 1050을 돌려주고 앱은 1001~1050을 쓴다 -- 시드 구간과 겹치지 않는다.
SELECT setval('attendance_seq', 1000);
