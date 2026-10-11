package com.travel.planner.repository;

import com.travel.planner.entity.TransitStation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TransitStationRepository extends JpaRepository<TransitStation, Long> {
}
