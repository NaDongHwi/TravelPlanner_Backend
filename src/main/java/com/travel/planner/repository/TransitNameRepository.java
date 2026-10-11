package com.travel.planner.repository;

import com.travel.planner.entity.TransitName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TransitNameRepository extends JpaRepository<TransitName, Long> {
}
