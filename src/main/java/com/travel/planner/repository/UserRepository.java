package com.travel.planner.repository;

import com.travel.planner.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    // 이메일이 DB에 이미 존재하는지(중복 가입인지) 스프링이 알아서 검사해 줍니다.
    boolean existsByEmail(String email);
}