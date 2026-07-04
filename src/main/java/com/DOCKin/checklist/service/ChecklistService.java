package com.DOCKin.checklist.service;

import com.DOCKin.checklist.dto.ChecklistCreateRequestDto;
import com.DOCKin.checklist.dto.ChecklistItemRequestDto;
import com.DOCKin.checklist.dto.ChecklistItemSimpleResponseDto;
import com.DOCKin.checklist.dto.ChecklistItemUpdateRequestDto;
import com.DOCKin.checklist.dto.ChecklistResponseDto;
import com.DOCKin.checklist.dto.ChecklistUpdateRequestDto;
import com.DOCKin.checklist.model.Checklist;
import com.DOCKin.checklist.model.ChecklistItem;
import com.DOCKin.checklist.repository.ChecklistItemRepository;
import com.DOCKin.checklist.repository.ChecklistRepository;
import com.DOCKin.checklist.repository.ChecklistResultRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.worklog.model.Equipment;
import com.DOCKin.worklog.repository.EquipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

// 체크리스트 템플릿(Checklist/ChecklistItem) 관리자 CRUD.
// 관리자 권한 체크는 이 프로젝트 관례대로 서비스 레이어에서 수동으로 한다(@PreAuthorize 미사용, SafetyCourseService 패턴).
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChecklistService {
    private final ChecklistRepository checklistRepository;
    private final ChecklistItemRepository checklistItemRepository;
    private final ChecklistResultRepository checklistResultRepository;
    private final EquipmentRepository equipmentRepository;
    private final MemberRepository memberRepository;

    private Member requireAdmin(String userId) {
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (member.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.CHECKLIST_AUTHOR);
        }
        return member;
    }

    private ChecklistItem requireItemOfChecklist(Integer checklistId, Integer itemId) {
        ChecklistItem item = checklistItemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_ITEM_NOT_FOUND));
        if (!item.getChecklist().getChecklistId().equals(checklistId)) {
            throw new BusinessException(ErrorCode.CHECKLIST_ITEM_MISMATCH);
        }
        return item;
    }

    @Transactional
    public ChecklistResponseDto createChecklist(String userId, ChecklistCreateRequestDto dto) {
        requireAdmin(userId);

        Equipment equipment = equipmentRepository.findById(dto.getEquipmentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EQUIPMENT_NOT_FOUND));

        if (checklistRepository.existsByEquipment_EquipmentIdAndPhase(dto.getEquipmentId(), dto.getPhase())) {
            throw new BusinessException(ErrorCode.CHECKLIST_ALREADY_EXISTS);
        }

        Checklist checklist = Checklist.builder()
                .equipment(equipment)
                .title(dto.getTitle())
                .phase(dto.getPhase())
                .build();
        Checklist saved = checklistRepository.save(checklist);

        List<ChecklistItem> items = dto.getItems().stream()
                .map(itemDto -> ChecklistItem.builder()
                        .checklist(saved)
                        .content(itemDto.getContent())
                        .sequence(itemDto.getSequence())
                        .build())
                .collect(Collectors.toList());
        List<ChecklistItem> savedItems = checklistItemRepository.saveAll(items);

        return ChecklistResponseDto.fromEntity(saved, savedItems);
    }

    public ChecklistResponseDto getChecklist(Integer checklistId) {
        Checklist checklist = checklistRepository.findById(checklistId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_NOT_FOUND));
        List<ChecklistItem> items = checklistItemRepository
                .findByChecklist_ChecklistIdOrderBySequenceAscItemIdAsc(checklistId);
        return ChecklistResponseDto.fromEntity(checklist, items);
    }

    @Transactional
    public ChecklistResponseDto updateChecklist(String userId, Integer checklistId, ChecklistUpdateRequestDto dto) {
        requireAdmin(userId);

        Checklist checklist = checklistRepository.findById(checklistId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_NOT_FOUND));

        if (dto.getPhase() != null && dto.getPhase() != checklist.getPhase()) {
            if (checklistRepository.existsByEquipment_EquipmentIdAndPhase(
                    checklist.getEquipment().getEquipmentId(), dto.getPhase())) {
                throw new BusinessException(ErrorCode.CHECKLIST_ALREADY_EXISTS);
            }
            checklist.setPhase(dto.getPhase());
        }
        if (dto.getTitle() != null) {
            checklist.setTitle(dto.getTitle());
        }

        Checklist saved = checklistRepository.save(checklist);
        List<ChecklistItem> items = checklistItemRepository
                .findByChecklist_ChecklistIdOrderBySequenceAscItemIdAsc(checklistId);
        return ChecklistResponseDto.fromEntity(saved, items);
    }

    @Transactional
    public void deleteChecklist(String userId, Integer checklistId) {
        requireAdmin(userId);

        Checklist checklist = checklistRepository.findById(checklistId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_NOT_FOUND));

        if (checklistResultRepository.existsByChecklistItem_Checklist_ChecklistId(checklistId)) {
            throw new BusinessException(ErrorCode.CHECKLIST_HAS_RESULTS);
        }

        List<ChecklistItem> items = checklistItemRepository
                .findByChecklist_ChecklistIdOrderBySequenceAscItemIdAsc(checklistId);
        checklistItemRepository.deleteAll(items);
        checklistRepository.delete(checklist);
    }

    @Transactional
    public ChecklistItemSimpleResponseDto addItem(String userId, Integer checklistId, ChecklistItemRequestDto dto) {
        requireAdmin(userId);

        Checklist checklist = checklistRepository.findById(checklistId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_NOT_FOUND));

        ChecklistItem item = ChecklistItem.builder()
                .checklist(checklist)
                .content(dto.getContent())
                .sequence(dto.getSequence())
                .build();

        return ChecklistItemSimpleResponseDto.fromEntity(checklistItemRepository.save(item));
    }

    @Transactional
    public ChecklistItemSimpleResponseDto updateItem(String userId, Integer checklistId, Integer itemId,
                                                       ChecklistItemUpdateRequestDto dto) {
        requireAdmin(userId);

        ChecklistItem item = requireItemOfChecklist(checklistId, itemId);

        if (dto.getContent() != null) {
            item.setContent(dto.getContent());
        }
        if (dto.getSequence() != null) {
            item.setSequence(dto.getSequence());
        }

        return ChecklistItemSimpleResponseDto.fromEntity(checklistItemRepository.save(item));
    }

    @Transactional
    public void deleteItem(String userId, Integer checklistId, Integer itemId) {
        requireAdmin(userId);

        ChecklistItem item = requireItemOfChecklist(checklistId, itemId);

        if (checklistResultRepository.existsByChecklistItem_ItemId(itemId)) {
            throw new BusinessException(ErrorCode.CHECKLIST_ITEM_HAS_RESULTS);
        }

        checklistItemRepository.delete(item);
    }
}
