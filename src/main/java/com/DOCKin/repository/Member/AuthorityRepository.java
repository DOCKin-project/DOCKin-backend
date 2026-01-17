package com.DOCKin.repository.Member;

import com.DOCKin.model.Member.Authority;
import com.DOCKin.model.Member.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuthorityRepository extends JpaRepository<Authority,Long> {
    List<Authority> findByMember(Member member);
}
