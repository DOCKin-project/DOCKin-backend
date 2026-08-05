package com.DOCKin.worklog.service;

import com.DOCKin.ai.service.SttService;
import com.DOCKin.global.file.S3PresignedService;
import com.DOCKin.worklog.dto.WorkLogsCreateRequestDto;
import com.DOCKin.worklog.dto.WorkLogsUpdateRequestDto;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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

    //전체 게시물 조회
    @Transactional(readOnly = true)
    public Page<WorkLogDto> readWorklog(String userId, Pageable pageable){
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));

        String area = member.getShipYardArea();
       List<Member> areaMembers= memberRepository.findByShipYardArea(area);
       Page<WorkLog> logs = workLogsRepository.findByMemberIn(areaMembers,pageable);

       return logs.map(WorkLogDto::from);
    }

    //다른 작업자의 작업일지 조회기능
    @Transactional(readOnly = true)
    public Page<WorkLogDto> readOtherWorklog(String currentuserId, String targetUserId, Pageable pageable){
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

        Page<WorkLog> workLogs = workLogsRepository.findAllByMemberUserId(targetUserId,pageable);

        return workLogs.map(WorkLogDto::from);
    }

    //키워드로 게시물 조회
    @Transactional(readOnly = true)
    public Page<WorkLogDto> searchByKeyword(String keyword,Pageable pageable){
        Page<WorkLog> workLog = workLogsRepository.searchWorkLogs(keyword,pageable);
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
