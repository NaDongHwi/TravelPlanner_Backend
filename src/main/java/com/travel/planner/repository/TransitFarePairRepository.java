package com.travel.planner.repository;

import com.travel.planner.entity.TransitFarePair;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransitFarePairRepository extends JpaRepository<TransitFarePair, Long> {
    List<TransitFarePair> findBySource(String source);
    List<TransitFarePair> findByGroupName(String groupName);
}
