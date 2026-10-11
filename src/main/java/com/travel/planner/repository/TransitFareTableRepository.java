package com.travel.planner.repository;

import com.travel.planner.entity.TransitFareTable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransitFareTableRepository extends JpaRepository<TransitFareTable, Long> {
    List<TransitFareTable> findBySource(String source);
    List<TransitFareTable> findByTableKey(String tableKey);
}
