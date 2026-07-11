package com.travel.planner.repository;

import com.travel.planner.entity.Log;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface LogRepository extends JpaRepository<Log, Long> {
    void deleteByErrorTimeBefore(LocalDateTime time);
}