package com.DOCKin.safetyCourse.service;

import com.DOCKin.safetyCourse.dto.CompletedLabel;
import com.DOCKin.safetyCourse.dto.SafetyCourseWorkerResponseDto;
import com.DOCKin.safetyCourse.dto.SafetyWatchStatusRequestDto;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.model.Member;
import com.DOCKin.safetyCourse.model.SafetyCourse;
import com.DOCKin.safetyCourse.model.SafetyEnrollment;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.safetyCourse.repository.SafetyCourseRepository;
import com.DOCKin.safetyCourse.repository.SafetyEnrollmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SafetyTrainingService {
    private final SafetyCourseRepository safetyCourseRepository;
    private final SafetyEnrollmentRepository safetyEnrollmentRepository;
    private final MemberRepository memberRepository;

    //사용자 미이수 영상 조회
    @Transactional(readOnly = true)
    public List<SafetyCourseWorkerResponseDto> searchUnwatchedVideo(String userId){
        List<SafetyCourse> safetyCourses= safetyCourseRepository.findAll();
        List<SafetyEnrollment> safetyEnrollments= safetyEnrollmentRepository.findAllByUserIdUserId(userId);

        return safetyCourses.stream().map(
                course->{
                    SafetyEnrollment enrollment =safetyEnrollments.stream()
                            .filter(e->e.getCourseId().getCourseId().equals(course.getCourseId()))
                            .findFirst()
                            .orElse(null);
                    return SafetyCourseWorkerResponseDto.from(course,enrollment);
                }).filter(dto->dto.getStatus()!= CompletedLabel.WATCHED)
                .collect(Collectors.toList());


    }

    /**
     * 사용자 영상 조회 완료. <b>멱등이다</b> — 같은 교육을 다시 완료해도, 동시에 두 번 보내도 204, 행은 하나 (#141).
     *
     * <p>순서대로 두 번 보내면 예전에도 기존 행을 찾아 갱신해 204였다. 동시 요청도 같은 결과여야 하므로 409가 아니라
     * 멱등으로 정했다. 예전 "찾고-없으면-만들기"는 동시 요청에서 행이 둘이 됐고, 그 뒤 단건 조회가 500이었다.
     * 이제 기록은 {@link SafetyEnrollmentRepository#upsertWatched} 한 문장이다.
     *
     * <p>사용자·교육 존재 검사는 그대로 앞에 둔다 — 없는 교육이면 FK 위반 500이 아니라 404.
     */
    @Transactional
    public void completedViewVideo(String userId,SafetyWatchStatusRequestDto dto){
        memberRepository.findByUserId(userId)
                .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!safetyCourseRepository.existsById(dto.getCourseId())) {
            throw new BusinessException(ErrorCode.SAFETYCOURSE_NOT_FOUND);
        }
        safetyEnrollmentRepository.upsertWatched(userId, dto.getCourseId(), LocalDateTime.now());
    }
}
