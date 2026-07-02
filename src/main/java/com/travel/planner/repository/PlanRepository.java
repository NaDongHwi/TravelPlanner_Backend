package com.travel.planner.repository;

import com.travel.planner.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlanRepository extends JpaRepository<Plan, Long> {

    // 나중에 마이페이지에서 '내 여행 목록'을 불러올 때 사용할 메서드
    List<Plan> findAllByUserEmail(String email);

}