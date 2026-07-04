package com.DOCKin.checklist.service;

import com.DOCKin.checklist.dto.ChecklistDetailResponseDto;
import com.DOCKin.checklist.dto.ChecklistItemStatusResponseDto;
import com.DOCKin.checklist.dto.ChecklistResultResponseDto;
import com.DOCKin.checklist.model.Checklist;
import com.DOCKin.checklist.model.ChecklistItem;
import com.DOCKin.checklist.model.ChecklistPhase;
import com.DOCKin.checklist.model.ChecklistResult;
import com.DOCKin.checklist.repository.ChecklistItemRepository;
import com.DOCKin.checklist.repository.ChecklistRepository;
import com.DOCKin.checklist.repository.ChecklistResultRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.worklog.repository.EquipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// 사용자용 체크리스트 조회/체크 처리. checklist_results는 append-only라 체크/해제는 항상 새 행을 INSERT하고,
// "현재 상태"는 항목별 최신 결과를 배치 쿼리 1번으로 가져와 병합한다(N+1 방지).
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChecklistStatusService {
    private final ChecklistRepository checklistRepository;
    private final ChecklistItemRepository checklistItemRepository;
    private final ChecklistResultRepository checklistResultRepository;
    private final EquipmentRepository equipmentRepository;
    private final MemberRepository memberRepository;

    public ChecklistDetailResponseDto getChecklistStatus(Long equipmentId, ChecklistPhase phase) {
        equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EQUIPMENT_NOT_FOUND));

        Checklist checklist = checklistRepository.findByEquipment_EquipmentIdAndPhase(equipmentId, phase)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_NOT_FOUND));

        List<ChecklistItem> items = checklistItemRepository
                .findByChecklist_ChecklistIdOrderBySequenceAscItemIdAsc(checklist.getChecklistId());

        Map<Integer, ChecklistResult> latestByItemId = checklistResultRepository
                .findLatestResultsByChecklistId(checklist.getChecklistId()).stream()
                .collect(Collectors.toMap(r -> r.getChecklistItem().getItemId(), Function.identity()));

        List<ChecklistItemStatusResponseDto> itemDtos = items.stream()
                .map(item -> {
                    ChecklistResult latest = latestByItemId.get(item.getItemId());
                    return ChecklistItemStatusResponseDto.builder()
                            .itemId(item.getItemId())
                            .content(item.getContent())
                            .sequence(item.getSequence())
                            .checked(latest != null && Boolean.TRUE.equals(latest.getIsChecked()))
                            .lastCheckedAt(latest != null ? latest.getCheckedAt() : null)
                            .lastCheckedBy(latest != null ? latest.getMember().getUserId() : null)
                            .build();
                })
                .collect(Collectors.toList());

        return ChecklistDetailResponseDto.of(checklist, itemDtos);
    }

    @Transactional
    public ChecklistResultResponseDto checkItem(String userId, Integer checklistId, Integer itemId,
                                                 boolean isChecked) {
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        checklistRepository.findById(checklistId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_NOT_FOUND));

        ChecklistItem item = checklistItemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_ITEM_NOT_FOUND));

        if (!item.getChecklist().getChecklistId().equals(checklistId)) {
            throw new BusinessException(ErrorCode.CHECKLIST_ITEM_MISMATCH);
        }

        ChecklistResult result = ChecklistResult.builder()
                .checklistItem(item)
                .member(member)
                .isChecked(isChecked)
                .build();

        return ChecklistResultResponseDto.fromEntity(checklistResultRepository.save(result));
    }
}
