package com.DOCKin.repository.Member;

import com.DOCKin.model.Member.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MemberRepository extends JpaRepository<Member,String> {
    Optional<Member> findByUserId(String userId);
    List<Member> findByShipYardArea(String shipYardArea);

}
