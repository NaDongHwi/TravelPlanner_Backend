package com.travel.planner.repository;

import com.travel.planner.entity.Itinerary;
import com.travel.planner.entity.Traffic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

@Repository
public interface TrafficRepository extends JpaRepository<Traffic, Long> {

    // 일정(Itinerary)을 지우기 전에 그 일정에 달린 이동 정보를 먼저 지운다 (FK 제약)
    @Transactional
    void deleteByItineraryIn(Collection<Itinerary> itineraries);
}
