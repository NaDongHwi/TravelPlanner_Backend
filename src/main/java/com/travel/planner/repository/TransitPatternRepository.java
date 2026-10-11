package com.travel.planner.repository;

import com.travel.planner.entity.TransitPattern;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransitPatternRepository extends JpaRepository<TransitPattern, Long> {

    /** 그 피드의 노선 패턴을 한 번의 DELETE 로 지운다 (호출하는 쪽에서 트랜잭션을 연다) */
    @Modifying
    @Query("DELETE FROM TransitPattern p WHERE p.feedKey = :feedKey")
    int deleteFeed(@Param("feedKey") String feedKey);

    @Query("SELECT DISTINCT p.routeName FROM TransitPattern p")
    List<String> findRouteNames();

    @Query("SELECT DISTINCT p.agency FROM TransitPattern p")
    List<String> findAgencies();
}
