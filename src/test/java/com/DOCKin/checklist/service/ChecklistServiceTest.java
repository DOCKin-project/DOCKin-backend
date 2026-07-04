package com.DOCKin.checklist.service;

import com.DOCKin.checklist.dto.ChecklistCreateRequestDto;
import com.DOCKin.checklist.dto.ChecklistItemRequestDto;
import com.DOCKin.checklist.dto.ChecklistItemUpdateRequestDto;
import com.DOCKin.checklist.dto.ChecklistResponseDto;
import com.DOCKin.checklist.dto.ChecklistUpdateRequestDto;
import com.DOCKin.checklist.model.Checklist;
import com.DOCKin.checklist.model.ChecklistItem;
import com.DOCKin.checklist.model.ChecklistPhase;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChecklistServiceTest {

    @Mock
    private ChecklistRepository checklistRepository;
    @Mock
    private ChecklistItemRepository checklistItemRepository;
    @Mock
    private ChecklistResultRepository checklistResultRepository;
    @Mock
    private EquipmentRepository equipmentRepository;
    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private ChecklistService checklistService;

    private static final String ADMIN_ID = "admin1";
    private static final String USER_ID = "user1";

    private Member admin() {
        return Member.builder().userId(ADMIN_ID).role(UserRole.ADMIN).build();
    }

    private Member normalUser() {
        return Member.builder().userId(USER_ID).role(UserRole.USER).build();
    }

    private Equipment equipment() {
        return Equipment.builder().equipmentId(1L).name("크레인").qrCode("QR1").nfcTag("NFC1").build();
    }

    private Checklist checklist(Integer checklistId, ChecklistPhase phase) {
        return Checklist.builder()
                .checklistId(checklistId)
                .equipment(equipment())
                .title("작업 전 점검")
                .phase(phase)
                .build();
    }

    @Test
    @DisplayName("관리자가 아닌 사용자가 체크리스트를 생성하면 CHECKLIST_AUTHOR 예외가 발생한다")
    void createChecklist_notAdmin_throwsAuthorException() {
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(normalUser()));
        ChecklistCreateRequestDto dto = ChecklistCreateRequestDto.builder()
                .equipmentId(1L).title("t").phase(ChecklistPhase.PRE)
                .items(List.of(ChecklistItemRequestDto.builder().content("c").sequence(1).build()))
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checklistService.createChecklist(USER_ID, dto));

        assertEquals(ErrorCode.CHECKLIST_AUTHOR, ex.getErrorCode());
        verify(equipmentRepository, never()).findById(any());
    }

    @Test
    @DisplayName("존재하지 않는 장비로 체크리스트를 생성하면 EQUIPMENT_NOT_FOUND 예외가 발생한다")
    void createChecklist_equipmentNotFound_throwsException() {
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(equipmentRepository.findById(1L)).thenReturn(Optional.empty());
        ChecklistCreateRequestDto dto = ChecklistCreateRequestDto.builder()
                .equipmentId(1L).title("t").phase(ChecklistPhase.PRE)
                .items(List.of(ChecklistItemRequestDto.builder().content("c").sequence(1).build()))
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checklistService.createChecklist(ADMIN_ID, dto));

        assertEquals(ErrorCode.EQUIPMENT_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("동일 장비+단계 조합의 체크리스트가 이미 있으면 CHECKLIST_ALREADY_EXISTS 예외가 발생한다")
    void createChecklist_duplicate_throwsException() {
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(equipmentRepository.findById(1L)).thenReturn(Optional.of(equipment()));
        when(checklistRepository.existsByEquipment_EquipmentIdAndPhase(1L, ChecklistPhase.PRE)).thenReturn(true);
        ChecklistCreateRequestDto dto = ChecklistCreateRequestDto.builder()
                .equipmentId(1L).title("t").phase(ChecklistPhase.PRE)
                .items(List.of(ChecklistItemRequestDto.builder().content("c").sequence(1).build()))
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checklistService.createChecklist(ADMIN_ID, dto));

        assertEquals(ErrorCode.CHECKLIST_ALREADY_EXISTS, ex.getErrorCode());
        verify(checklistRepository, never()).save(any());
    }

    @Test
    @DisplayName("정상 요청 시 체크리스트와 항목들이 함께 저장되고 응답 dto의 항목 개수가 일치한다")
    void createChecklist_success_savesChecklistAndItems() {
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(equipmentRepository.findById(1L)).thenReturn(Optional.of(equipment()));
        when(checklistRepository.existsByEquipment_EquipmentIdAndPhase(1L, ChecklistPhase.PRE)).thenReturn(false);
        Checklist saved = checklist(10, ChecklistPhase.PRE);
        when(checklistRepository.save(any(Checklist.class))).thenReturn(saved);
        when(checklistItemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ChecklistCreateRequestDto dto = ChecklistCreateRequestDto.builder()
                .equipmentId(1L).title("작업 전 점검").phase(ChecklistPhase.PRE)
                .items(List.of(
                        ChecklistItemRequestDto.builder().content("안전모 착용").sequence(1).build(),
                        ChecklistItemRequestDto.builder().content("장갑 착용").sequence(2).build()))
                .build();

        ChecklistResponseDto response = checklistService.createChecklist(ADMIN_ID, dto);

        assertEquals(2, response.getItems().size());
        verify(checklistItemRepository, times(1)).saveAll(any());
    }

    @Test
    @DisplayName("존재하지 않는 체크리스트를 수정하려 하면 CHECKLIST_NOT_FOUND 예외가 발생한다")
    void updateChecklist_notFound_throwsException() {
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(checklistRepository.findById(10)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checklistService.updateChecklist(ADMIN_ID, 10, ChecklistUpdateRequestDto.builder().build()));

        assertEquals(ErrorCode.CHECKLIST_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("관리자가 아닌 사용자가 체크리스트를 수정하면 CHECKLIST_AUTHOR 예외가 발생한다")
    void updateChecklist_notAdmin_throwsException() {
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(normalUser()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checklistService.updateChecklist(USER_ID, 10, ChecklistUpdateRequestDto.builder().build()));

        assertEquals(ErrorCode.CHECKLIST_AUTHOR, ex.getErrorCode());
        verify(checklistRepository, never()).findById(any());
    }

    @Test
    @DisplayName("phase 변경 시 변경 대상 조합이 이미 존재하면 CHECKLIST_ALREADY_EXISTS 예외가 발생한다")
    void updateChecklist_phaseCollision_throwsException() {
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(checklistRepository.findById(10)).thenReturn(Optional.of(checklist(10, ChecklistPhase.PRE)));
        when(checklistRepository.existsByEquipment_EquipmentIdAndPhase(1L, ChecklistPhase.POST)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checklistService.updateChecklist(ADMIN_ID, 10,
                        ChecklistUpdateRequestDto.builder().phase(ChecklistPhase.POST).build()));

        assertEquals(ErrorCode.CHECKLIST_ALREADY_EXISTS, ex.getErrorCode());
        verify(checklistRepository, never()).save(any());
    }

    @Test
    @DisplayName("점검 기록이 존재하는 체크리스트를 삭제하면 CHECKLIST_HAS_RESULTS 예외가 발생하고 delete가 호출되지 않는다")
    void deleteChecklist_hasResults_throwsException() {
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(checklistRepository.findById(10)).thenReturn(Optional.of(checklist(10, ChecklistPhase.PRE)));
        when(checklistResultRepository.existsByChecklistItem_Checklist_ChecklistId(10)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checklistService.deleteChecklist(ADMIN_ID, 10));

        assertEquals(ErrorCode.CHECKLIST_HAS_RESULTS, ex.getErrorCode());
        verify(checklistRepository, never()).delete(any());
    }

    @Test
    @DisplayName("점검 기록이 없는 체크리스트는 항목과 함께 정상적으로 삭제된다")
    void deleteChecklist_success_deletesItemsAndChecklist() {
        Checklist target = checklist(10, ChecklistPhase.PRE);
        List<ChecklistItem> items = List.of(
                ChecklistItem.builder().itemId(1).checklist(target).content("c1").sequence(1).build());
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(checklistRepository.findById(10)).thenReturn(Optional.of(target));
        when(checklistResultRepository.existsByChecklistItem_Checklist_ChecklistId(10)).thenReturn(false);
        when(checklistItemRepository.findByChecklist_ChecklistIdOrderBySequenceAscItemIdAsc(10)).thenReturn(items);

        checklistService.deleteChecklist(ADMIN_ID, 10);

        verify(checklistItemRepository, times(1)).deleteAll(items);
        verify(checklistRepository, times(1)).delete(target);
    }

    @Test
    @DisplayName("다른 체크리스트에 속한 항목을 수정하려 하면 CHECKLIST_ITEM_MISMATCH 예외가 발생한다")
    void updateItem_mismatchedChecklist_throwsException() {
        Checklist otherChecklist = checklist(99, ChecklistPhase.PRE);
        ChecklistItem item = ChecklistItem.builder().itemId(5).checklist(otherChecklist).content("c").sequence(1).build();
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(checklistItemRepository.findById(5)).thenReturn(Optional.of(item));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checklistService.updateItem(ADMIN_ID, 10, 5,
                        ChecklistItemUpdateRequestDto.builder().content("new").build()));

        assertEquals(ErrorCode.CHECKLIST_ITEM_MISMATCH, ex.getErrorCode());
    }

    @Test
    @DisplayName("점검 기록이 있는 항목을 삭제하려 하면 CHECKLIST_ITEM_HAS_RESULTS 예외가 발생한다")
    void deleteItem_hasResults_throwsException() {
        Checklist target = checklist(10, ChecklistPhase.PRE);
        ChecklistItem item = ChecklistItem.builder().itemId(5).checklist(target).content("c").sequence(1).build();
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(checklistItemRepository.findById(5)).thenReturn(Optional.of(item));
        when(checklistResultRepository.existsByChecklistItem_ItemId(5)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checklistService.deleteItem(ADMIN_ID, 10, 5));

        assertEquals(ErrorCode.CHECKLIST_ITEM_HAS_RESULTS, ex.getErrorCode());
        verify(checklistItemRepository, never()).delete(any());
    }

    @Test
    @DisplayName("점검 기록이 없는 항목은 정상적으로 삭제된다")
    void deleteItem_success_deletesItem() {
        Checklist target = checklist(10, ChecklistPhase.PRE);
        ChecklistItem item = ChecklistItem.builder().itemId(5).checklist(target).content("c").sequence(1).build();
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(checklistItemRepository.findById(5)).thenReturn(Optional.of(item));
        when(checklistResultRepository.existsByChecklistItem_ItemId(5)).thenReturn(false);

        checklistService.deleteItem(ADMIN_ID, 10, 5);

        verify(checklistItemRepository, times(1)).delete(item);
    }
}
