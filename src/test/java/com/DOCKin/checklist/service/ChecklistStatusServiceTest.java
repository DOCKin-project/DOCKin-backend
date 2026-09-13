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
import com.DOCKin.worklog.model.Equipment;
import com.DOCKin.worklog.repository.EquipmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChecklistStatusServiceTest {

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
    private ChecklistStatusService checklistStatusService;

    private static final Long EQUIPMENT_ID = 1L;
    private static final String USER_ID = "user1";

    private Equipment equipment() {
        return Equipment.builder().equipmentId(EQUIPMENT_ID).name("크레인").qrCode("QR1").nfcTag("NFC1").build();
    }

    private Checklist checklist() {
        return Checklist.builder()
                .checklistId(10)
                .equipment(equipment())
                .title("작업 전 점검")
                .phase(ChecklistPhase.PRE)
                .build();
    }

    private Member member() {
        return Member.builder().userId(USER_ID).build();
    }

    @Test
    @DisplayName("존재하지 않는 장비로 조회하면 EQUIPMENT_NOT_FOUND 예외가 발생한다")
    void getChecklistStatus_equipmentNotFound_throwsException() {
        when(equipmentRepository.findById(EQUIPMENT_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checklistStatusService.getChecklistStatus(EQUIPMENT_ID, ChecklistPhase.PRE));

        assertEquals(ErrorCode.EQUIPMENT_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("해당 장비+단계 조합의 체크리스트가 없으면 CHECKLIST_NOT_FOUND 예외가 발생한다")
    void getChecklistStatus_checklistNotFound_throwsException() {
        when(equipmentRepository.findById(EQUIPMENT_ID)).thenReturn(Optional.of(equipment()));
        when(checklistRepository.findByEquipment_EquipmentIdAndPhase(EQUIPMENT_ID, ChecklistPhase.PRE))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checklistStatusService.getChecklistStatus(EQUIPMENT_ID, ChecklistPhase.PRE));

        assertEquals(ErrorCode.CHECKLIST_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("점검 이력이 없는 항목은 checked=false, lastCheckedAt=null로 반환된다")
    void getChecklistStatus_noHistory_returnsUncheckedItem() {
        Checklist checklist = checklist();
        ChecklistItem item = ChecklistItem.builder().itemId(1).checklist(checklist).content("c1").sequence(1).build();
        when(equipmentRepository.findById(EQUIPMENT_ID)).thenReturn(Optional.of(equipment()));
        when(checklistRepository.findByEquipment_EquipmentIdAndPhase(EQUIPMENT_ID, ChecklistPhase.PRE))
                .thenReturn(Optional.of(checklist));
        when(checklistItemRepository.findByChecklist_ChecklistIdOrderBySequenceAscItemIdAsc(10))
                .thenReturn(List.of(item));
        when(checklistResultRepository.findLatestResultsByChecklistId(10)).thenReturn(List.of());

        ChecklistDetailResponseDto response = checklistStatusService.getChecklistStatus(EQUIPMENT_ID, ChecklistPhase.PRE);

        ChecklistItemStatusResponseDto itemDto = response.getItems().get(0);
        assertFalse(itemDto.isChecked());
        assertNull(itemDto.getLastCheckedAt());
        assertNull(itemDto.getLastCheckedBy());
    }

    @Test
    @DisplayName("여러 번 체크/해제된 항목은 findLatestResultsByChecklistId가 반환한 최신 결과의 상태로 매핑된다")
    void getChecklistStatus_withHistory_reflectsLatestResult() {
        Checklist checklist = checklist();
        ChecklistItem item = ChecklistItem.builder().itemId(1).checklist(checklist).content("c1").sequence(1).build();
        Member member = member();
        ChecklistResult latest = ChecklistResult.builder()
                .resultId(99).checklistItem(item).member(member).isChecked(true).build();
        when(equipmentRepository.findById(EQUIPMENT_ID)).thenReturn(Optional.of(equipment()));
        when(checklistRepository.findByEquipment_EquipmentIdAndPhase(EQUIPMENT_ID, ChecklistPhase.PRE))
                .thenReturn(Optional.of(checklist));
        when(checklistItemRepository.findByChecklist_ChecklistIdOrderBySequenceAscItemIdAsc(10))
                .thenReturn(List.of(item));
        when(checklistResultRepository.findLatestResultsByChecklistId(10)).thenReturn(List.of(latest));

        ChecklistDetailResponseDto response = checklistStatusService.getChecklistStatus(EQUIPMENT_ID, ChecklistPhase.PRE);

        ChecklistItemStatusResponseDto itemDto = response.getItems().get(0);
        assertTrue(itemDto.isChecked());
        assertEquals(USER_ID, itemDto.getLastCheckedBy());
    }

    @Test
    @DisplayName("존재하지 않는 사용자가 항목을 체크하려 하면 USER_NOT_FOUND 예외가 발생한다")
    void checkItem_userNotFound_throwsException() {
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checklistStatusService.checkItem(USER_ID, 10, 1, true));

        assertEquals(ErrorCode.USER_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("존재하지 않는 항목을 체크하려 하면 CHECKLIST_ITEM_NOT_FOUND 예외가 발생한다")
    void checkItem_itemNotFound_throwsException() {
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member()));
        when(checklistRepository.findById(10)).thenReturn(Optional.of(checklist()));
        when(checklistItemRepository.findById(1)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checklistStatusService.checkItem(USER_ID, 10, 1, true));

        assertEquals(ErrorCode.CHECKLIST_ITEM_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("다른 체크리스트에 속한 항목을 체크하려 하면 CHECKLIST_ITEM_MISMATCH 예외가 발생한다")
    void checkItem_mismatchedChecklist_throwsException() {
        Checklist otherChecklist = Checklist.builder()
                .checklistId(99).equipment(equipment()).title("다른 체크리스트").phase(ChecklistPhase.POST).build();
        ChecklistItem item = ChecklistItem.builder().itemId(1).checklist(otherChecklist).content("c1").sequence(1).build();
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member()));
        when(checklistRepository.findById(10)).thenReturn(Optional.of(checklist()));
        when(checklistItemRepository.findById(1)).thenReturn(Optional.of(item));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checklistStatusService.checkItem(USER_ID, 10, 1, true));

        assertEquals(ErrorCode.CHECKLIST_ITEM_MISMATCH, ex.getErrorCode());
        verify(checklistResultRepository, never()).save(any());
    }

    @Test
    @DisplayName("체크 요청 시 upsert가 아닌 새 ChecklistResult가 매번 저장된다")
    void checkItem_success_alwaysInsertsNewResult() {
        Checklist checklist = checklist();
        ChecklistItem item = ChecklistItem.builder().itemId(1).checklist(checklist).content("c1").sequence(1).build();
        Member member = member();
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member));
        when(checklistRepository.findById(10)).thenReturn(Optional.of(checklist));
        when(checklistItemRepository.findById(1)).thenReturn(Optional.of(item));
        when(checklistResultRepository.save(any(ChecklistResult.class))).thenAnswer(invocation -> {
            ChecklistResult arg = invocation.getArgument(0);
            return ChecklistResult.builder()
                    .resultId(1).checklistItem(arg.getChecklistItem()).member(arg.getMember())
                    .isChecked(arg.getIsChecked()).build();
        });

        ChecklistResultResponseDto response = checklistStatusService.checkItem(USER_ID, 10, 1, true);

        ArgumentCaptor<ChecklistResult> captor = ArgumentCaptor.forClass(ChecklistResult.class);
        verify(checklistResultRepository, times(1)).save(captor.capture());
        assertNull(captor.getValue().getResultId());
        assertTrue(captor.getValue().getIsChecked());
        assertTrue(response.getIsChecked());
    }
}
