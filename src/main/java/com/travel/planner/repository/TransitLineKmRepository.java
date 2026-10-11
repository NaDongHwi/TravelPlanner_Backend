package com.travel.planner.repository;

import com.travel.planner.entity.TransitLineKm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransitLineKmRepository extends JpaRepository<TransitLineKm, Long> {
    List<TransitLineKm> findBySource(String source);
    List<TransitLineKm> findByLineName(String lineName);
}
