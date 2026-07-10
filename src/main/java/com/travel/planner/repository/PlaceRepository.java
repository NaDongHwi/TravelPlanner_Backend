package com.travel.planner.repository;

import com.travel.planner.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PlaceRepository extends JpaRepository<Place, Long> {
    List<Place> findByCity(String city);

    List<Place> findByCityIn(List<String> cities);

    List<Place> findTop10ByCityAndCategory(String city, String category);

    boolean existsByPlaceId(String placeId);

    java.util.Optional<Place> findByPlaceId(String placeId);
}