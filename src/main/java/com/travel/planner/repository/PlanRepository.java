package com.travel.planner.repository;

import com.travel.planner.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PlanRepository extends JpaRepository<Plan, Long> {

    // 나중에 마이페이지에서 '내 여행 목록'을 불러올 때 사용할 메서드
    List<Plan> findAllByUserEmail(String email);

    // 일정표(PDF·재탐색·날씨 알림)에 필요한 연관 데이터를 한 번에 불러온다.
    // (트랜잭션 밖에서 plan.getItineraries() 를 건드려 LazyInitializationException 이 나던 문제 방지)
    @Query("SELECT DISTINCT p FROM Plan p LEFT JOIN FETCH p.user LEFT JOIN FETCH p.itineraries i LEFT JOIN FETCH i.place WHERE p.id = :id")
    Optional<Plan> findDetailById(@Param("id") Long id);

    @Query("SELECT DISTINCT p FROM Plan p LEFT JOIN FETCH p.user LEFT JOIN FETCH p.itineraries i LEFT JOIN FETCH i.place "
            + "WHERE p.startDate >= :from AND p.startDate <= :to")
    List<Plan> findDetailByStartDateBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

}
