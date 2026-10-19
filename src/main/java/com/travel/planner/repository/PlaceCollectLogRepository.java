package com.travel.planner.repository;

import com.travel.planner.entity.PlaceCollectLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PlaceCollectLogRepository extends JpaRepository<PlaceCollectLog, Long> {

    /** 지역 수집을 끝낸 도시 이름들 */
    @Query("SELECT DISTINCT l.city FROM PlaceCollectLog l")
    List<String> findCollectedCities();
}
