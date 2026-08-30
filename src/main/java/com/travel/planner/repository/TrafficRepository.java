package com.travel.planner.repository;

import com.travel.planner.entity.Traffic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TrafficRepository extends JpaRepository<Traffic, Long> {
}