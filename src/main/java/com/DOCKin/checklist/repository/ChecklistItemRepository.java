package com.DOCKin.checklist.repository;

import com.DOCKin.checklist.model.ChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, Integer> {
    List<ChecklistItem> findByChecklist_ChecklistIdOrderBySequenceAscItemIdAsc(Integer checklistId);
}
