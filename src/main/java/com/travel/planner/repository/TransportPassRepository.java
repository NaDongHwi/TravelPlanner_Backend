package com.travel.planner.repository;

import com.travel.planner.entity.TransportPass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TransportPassRepository extends JpaRepository<TransportPass, Long> {
    List<TransportPass> findByCity(String city);
}