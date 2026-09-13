package com.DOCKin.attendance.repository;

import com.DOCKin.attendance.model.WorkCalendar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface WorkCalendarRepository extends JpaRepository<WorkCalendar, LocalDate> {

    /** 연 단위 조회. 관리자 화면과 일괄 등록 시 기존 등록분을 확인하는 데 쓴다. */
    List<WorkCalendar> findByCalendarDateBetweenOrderByCalendarDate(LocalDate startDate, LocalDate endDate);
}
