package com.travel.planner.repository;

import com.travel.planner.entity.TransitLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TransitLinkRepository extends JpaRepository<TransitLink, Long> {
}
