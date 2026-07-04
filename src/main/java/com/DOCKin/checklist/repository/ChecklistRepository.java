package com.DOCKin.checklist.repository;

import com.DOCKin.checklist.model.Checklist;
import com.DOCKin.checklist.model.ChecklistPhase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChecklistRepository extends JpaRepository<Checklist, Integer> {
    Optional<Checklist> findByEquipment_EquipmentIdAndPhase(Long equipmentId, ChecklistPhase phase);
    boolean existsByEquipment_EquipmentIdAndPhase(Long equipmentId, ChecklistPhase phase);
}
