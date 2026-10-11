package com.travel.planner.repository;

import com.travel.planner.entity.TransitStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransitStopRepository extends JpaRepository<TransitStop, Long> {

    /** 그 피드의 정류장을 한 번의 DELETE 로 지운다 (호출하는 쪽에서 트랜잭션을 연다) */
    @Modifying
    @Query("DELETE FROM TransitStop s WHERE s.feedKey = :feedKey")
    int deleteFeed(@Param("feedKey") String feedKey);

    /** 한국어 표기가 아직 없는 정류장 이름 (읽기 자료가 없는 피드의 정류장. AI 번역 대상) */
    @Query("SELECT DISTINCT s.name FROM TransitStop s WHERE s.nameKo IS NULL")
    List<String> findNamesWithoutKorean();
}
