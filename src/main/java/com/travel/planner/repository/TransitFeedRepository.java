package com.travel.planner.repository;

import com.travel.planner.entity.TransitFeed;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransitFeedRepository extends JpaRepository<TransitFeed, Long> {

    List<TransitFeed> findByFeedKey(String feedKey);
}
