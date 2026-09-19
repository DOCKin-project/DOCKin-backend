package com.DOCKin.safetyCourse.service;

import com.DOCKin.safetyCourse.dto.SafetyCourseCreateRequestDto;
import com.DOCKin.safetyCourse.dto.SafetyCourseResponseDto;
import com.DOCKin.safetyCourse.dto.SafetyCourseUpdateRequestDto;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.safetyCourse.model.SafetyCourse;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.safetyCourse.repository.SafetyCourseRepository;
import com.DOCKin.safetyCourse.repository.SafetyEnrollmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@Slf4j
@RequiredArgsConstructor
public class SafetyCourseService {
    private final SafetyCourseRepository safetyCourseRepository;
    private final MemberRepository memberRepository;
    private final SafetyEnrollmentRepository safetyEnrollmentRepository;

    //교육 자료 등록
    @Transactional
    public SafetyCourseResponseDto createSafetyCourseResponse(SafetyCourseCreateRequestDto dto, String userId){
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));

        //관리자만 안전교육 생성 가능
        if(member.getRole()!= UserRole.ADMIN){
            throw new BusinessException(ErrorCode.SAFETYCOURSE_AUTHOR);
        }

        SafetyCourse safetyCourse = SafetyCourse.builder().
                title(dto.getTitle()).
                description(dto.getDescription()).
                materialUrl(dto.getMaterialUrl()).
                videoUrl(dto.getVideoUrl()).
                durationMinutes(dto.getDurationMinutes()).
                createdBy(member.getUserId()).build();

        SafetyCourse savedCourse = safetyCourseRepository.save(safetyCourse);

        return SafetyCourseResponseDto.fromEntity(savedCourse);
    }

    //교육 자료 수정
    @Transactional
    public SafetyCourseResponseDto reviseSafetyCourse(SafetyCourseUpdateRequestDto dto, String userId,Integer courseId){
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));

        SafetyCourse logs = safetyCourseRepository.findById(courseId)
                .orElseThrow(()->new BusinessException(ErrorCode.SAFETYCOURSE_NOT_FOUND));

        //관리자만 안전교육 수정 가능
        if(member.getRole()!= UserRole.ADMIN){
            throw new BusinessException(ErrorCode.SAFETYCOURSE_AUTHOR);
        }

        if(dto.getTitle()!=null){
            logs.setTitle(dto.getTitle());
        }
        if(dto.getDescription()!=null){
            logs.setDescription(dto.getDescription());
        }
        if(dto.getVideoUrl()!=null){
            logs.setVideoUrl(dto.getVideoUrl());
        }
        if(dto.getDurationMinutes()!=null){
            logs.setDurationMinutes(dto.getDurationMinutes());
        }
        // DTO에는 있는데 안 옮기고 있었다(#107). 나머지 필드와 같은 규칙 — null이면 그대로.
        if(dto.getMaterialUrl()!=null){
            logs.setMaterialUrl(dto.getMaterialUrl());
        }
        return SafetyCourseResponseDto.fromEntity(safetyCourseRepository.save(logs));
    }

    //전체 교육자료 조회
    @Transactional(readOnly = true)
    public Page<SafetyCourseResponseDto> readSafetyCourse(Pageable pageable){
        Page<SafetyCourse> safetyCourses = safetyCourseRepository.findAll(pageable);
        return safetyCourses.map(SafetyCourseResponseDto::fromEntity);
    }

    //키워드로 교육자료 조회
    @Transactional(readOnly = true)
    public Page<SafetyCourseResponseDto> searchSafetyCourse(String keyword,Pageable pageable){
        Page<SafetyCourse> search = safetyCourseRepository.searchByKeyword(
                keyword,
                pageable
        );

        return search.map(SafetyCourseResponseDto::fromEntity);
    }

    //특정 작성자가 쓴 교육자료 조회
    @Transactional(readOnly = true)
    public Page<SafetyCourseResponseDto> searchOtherSafetyCourse(String targetUserId, Pageable pageable){
       Page<SafetyCourse> safetyCourses = safetyCourseRepository.findByCreatedBy(targetUserId,pageable);
       return safetyCourses.map(SafetyCourseResponseDto::fromEntity);
    }

    /**
     * 특정 교육자료 삭제 (#107).
     *
     * <p><b>권한은 수정과 같은 ADMIN.</b> 전에는 삭제만 "작성자 본인"이라 작성자가 퇴사하면 그 교육은 아무도 못 지웠다.
     * 한 도메인에 권한 기준이 둘이면 어느 쪽이 의도인지 아무도 모른다 — 넓은 쪽(수정)에 맞춘다.
     *
     * <p><b>수강 기록이 있으면 409.</b> 수강 기록은 "누가 언제 안전교육을 봤는가"라 교육을 지우면 그 기록이 가리키는 게
     * 사라진다. 체크리스트의 {@code CHECKLIST_HAS_RESULTS}와 같은 결정. 전에는 FK(NO ACTION)에 걸려 500이었다.
     */
    @Transactional
    public void  deleteSafetyCourse(String userId, Integer courseId){
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));
        if(member.getRole()!= UserRole.ADMIN){
            throw new BusinessException(ErrorCode.SAFETYCOURSE_AUTHOR);
        }

        SafetyCourse safetyCourse =safetyCourseRepository.findById(courseId)
                .orElseThrow(()->new BusinessException(ErrorCode.SAFETYCOURSE_NOT_FOUND));

        if(safetyEnrollmentRepository.existsByCourseIdCourseId(courseId)){
            throw new BusinessException(ErrorCode.SAFETYCOURSE_HAS_ENROLLMENTS);
        }

        safetyCourseRepository.delete(safetyCourse);
    }

}
