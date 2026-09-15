package com.DOCKin.worklog.service;

import com.DOCKin.ai.service.SttService;
import com.DOCKin.global.file.S3PresignedService;
import com.DOCKin.worklog.dto.WorkLogsCreateRequestDto;
import com.DOCKin.worklog.dto.WorkLogsUpdateRequestDto;
import com.DOCKin.worklog.dto.WorkLogCursor;
import com.DOCKin.worklog.dto.WorkLogDto;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.worklog.model.Equipment;
import com.DOCKin.member.model.Member;
import com.DOCKin.worklog.model.WorkLogImage;
import com.DOCKin.worklog.model.WorkLog;
import com.DOCKin.worklog.repository.EquipmentRepository;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.worklog.repository.WorkLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class WorkLogsService {

    private final WorkLogRepository workLogsRepository;
    private final MemberRepository memberRepository;
    private final EquipmentRepository equipmentRepository;
    private final SttService sttService;
    private final S3PresignedService s3PresignedService;

    //게시물 작성
    @Transactional
    public WorkLogDto createWorklog(String userId,WorkLogsCreateRequestDto dto,List<MultipartFile> images){
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));

        Equipment equipment = equipmentRepository.findById(dto.getEquipmentId())
                .orElseThrow(()->new BusinessException(ErrorCode.EQUIPMENT_NOT_FOUND));

        WorkLog workLog = WorkLog.builder()
                .title(dto.getTitle())
                .logText(dto.getLogText())
                .equipment(equipment)
                .member(member)
                .build();

        if(images !=null && !images.isEmpty()){
            images.forEach(file->{

                String uploadedUrl = s3PresignedService.uploadImage(file);

                WorkLogImage image = WorkLogImage.builder()
                        .imageUrl(uploadedUrl)
                        .workLog(workLog)
                        .build();

                workLog.addImage(image);
            });
        }

        return WorkLogDto.from(workLogsRepository.save(workLog));
    }


    //stt용게시물 작성
    @Transactional
    public WorkLogDto createSttWorklog(String userId, WorkLogsCreateRequestDto dto, MultipartFile file,String token, List<MultipartFile> images){
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));

        Equipment equipment = equipmentRepository.findById(dto.getEquipmentId())
                .orElseThrow(()->new BusinessException(ErrorCode.EQUIPMENT_NOT_FOUND));

        String finalLogText = dto.getLogText();
        String finalAudioUrl = dto.getAudioFileUrl();

        if(file!=null && !file.isEmpty()){
            try{
                var sttResponse = sttService.processStt(file,"trace-"+userId,token,"ko").block();

                log.info("STT Response 객체: {}", sttResponse);

                if(sttResponse !=null && sttResponse.text()!=null){
                    finalLogText = sttResponse.text();
                }
                finalAudioUrl = "uploaded_"+file.getOriginalFilename();
            } catch(Exception e){
                log.error("stt변환 실패:{}"+e.getMessage());
            }
        }

        WorkLog workLog = WorkLog.builder()
                .title(dto.getTitle())
                .logText(finalLogText)
                .equipment(equipment)
                .audioFileUrl(finalAudioUrl)
                .member(member)
                .build();

        if(images !=null && !images.isEmpty()){
            images.forEach(imagefile->{

                String uploadedUrl = s3PresignedService.uploadImage(imagefile);

                WorkLogImage image = WorkLogImage.builder()
                        .imageUrl(uploadedUrl)
                        .workLog(workLog)
                        .build();

                workLog.addImage(image);
            });
        }

         return WorkLogDto.from(workLogsRepository.save(workLog));
    }

    /**
     * 세 목록이 공유하는 페이지 규칙 (DB-IMPROVEMENT-PLAN A4·D2).
     *
     * <p>정렬은 리포지토리 쿼리가 정한다({@code createdAt DESC, logId DESC}). 여기서 {@code Pageable}의
     * sort를 떼는 것은 채팅 {@code getChatHistory}와 같은 이유다 — 클라이언트 sort가 커서와 어긋나면
     * 페이지 경계에서 행이 겹치거나 빠진다. 커서가 있으면 page 번호도 무시한다: 커서가 곧 위치다.
     */
    private static Pageable sizeOnly(WorkLogCursor before, Pageable pageable) {
        int page = before == null ? pageable.getPageNumber() : 0;
        return PageRequest.of(page, pageable.getPageSize());
    }

    private static LocalDateTime beforeCreatedAt(WorkLogCursor c) { return c == null ? null : c.createdAt(); }
    private static Long beforeLogId(WorkLogCursor c) { return c == null ? null : c.logId(); }

    //전체 게시물 조회
    @Transactional(readOnly = true)
    public Slice<WorkLogDto> readWorklog(String userId, WorkLogCursor before, Pageable pageable){
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));

        String area = member.getShipYardArea();
       List<Member> areaMembers= memberRepository.findByShipYardArea(area);
       Slice<WorkLog> logs = workLogsRepository.findByMemberIn(areaMembers,
               beforeCreatedAt(before), beforeLogId(before), sizeOnly(before, pageable));

       return logs.map(WorkLogDto::from);
    }

    //다른 작업자의 작업일지 조회기능
    @Transactional(readOnly = true)
    public Slice<WorkLogDto> readOtherWorklog(String currentuserId, String targetUserId, WorkLogCursor before, Pageable pageable){
        // 내 사원번호
        Member member = memberRepository.findByUserId(currentuserId)
                .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));

        //검색하려는 사원번호
        Member target = memberRepository.findByUserId(targetUserId)
                .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));

        //같은 구역에 있는 사용자들만 검색이 가능하다
        if(!member.getShipYardArea().equals(target.getShipYardArea())){
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        Slice<WorkLog> workLogs = workLogsRepository.findAllByMemberUserId(targetUserId,
                beforeCreatedAt(before), beforeLogId(before), sizeOnly(before, pageable));

        return workLogs.map(WorkLogDto::from);
    }

    //키워드로 게시물 조회 - 목록과 같은 범위(같은 구역)만 (P2-18-10)
    @Transactional(readOnly = true)
    public Slice<WorkLogDto> searchByKeyword(String userId, String keyword, WorkLogCursor before, Pageable pageable){
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));
        List<Member> areaMembers = memberRepository.findByShipYardArea(member.getShipYardArea());
        Slice<WorkLog> workLog = workLogsRepository.searchWorkLogs(keyword, areaMembers,
                beforeCreatedAt(before), beforeLogId(before), sizeOnly(before, pageable));
        return workLog.map(WorkLogDto::from);
    }

    //게시물 수정
    @Transactional
    public WorkLogDto updateWorklog(String userId, Long logId, WorkLogsUpdateRequestDto dto, List<MultipartFile> images){
        WorkLog logs = workLogsRepository.findById(logId)
                .orElseThrow(()->new BusinessException(ErrorCode.LOG_NOT_FOUND));

        //작성자와 수정자가 같은지 확인
        if(!logs.getMember().getUserId().equals(userId)){
            throw new BusinessException(ErrorCode.NOT_LOG_AUTHOR);
        }

        if(dto.getTitle()!=null) logs.setTitle(dto.getTitle());
        if(dto.getLogText()!=null) logs.setLogText(dto.getLogText());
        if(images !=null && !images.isEmpty()){
            logs.getImages().clear();

            images.forEach(imagefile->{

                String uploadedUrl = s3PresignedService.uploadImage(imagefile);

                WorkLogImage image = WorkLogImage.builder()
                        .imageUrl(uploadedUrl)
                        .workLog(logs)
                        .build();

                logs.addImage(image);
            });
        }
        if(dto.getEquipmentId()!=null){
            Equipment equipment = equipmentRepository.findById(dto.getEquipmentId())
                    .orElseThrow(()->new BusinessException(ErrorCode.EQUIPMENT_NOT_FOUND));
            logs.setEquipment(equipment);
        }

        return WorkLogDto.from(logs);
    }

    //게시물 삭제
    @Transactional
    public void deleteWorklog(String userId, Long logId){
        WorkLog log = workLogsRepository.findById(logId)
                .orElseThrow(()->new BusinessException(ErrorCode.LOG_NOT_FOUND));

       //작성자와 같은지 확인
        if(!log.getMember().getUserId().equals(userId)){
            throw new BusinessException(ErrorCode.NOT_LOG_AUTHOR);
        }

      workLogsRepository.delete(log);
    }
}
