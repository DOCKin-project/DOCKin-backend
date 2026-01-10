package com.DOCKin.repository;

import com.DOCKin.model.Member.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User,String> {
}
