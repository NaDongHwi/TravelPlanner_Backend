package com.travel.planner.repository;

import com.travel.planner.entity.Place;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlaceRepository extends JpaRepository<Place, Long> {

    List<Place> findByCity(String city);

    List<Place> findByCityIn(List<String> cities);

    List<Place> findTop10ByCityAndCategory(String city, String category);

    boolean existsByPlaceId(String placeId);

    java.util.Optional<Place> findByPlaceId(String placeId);

    // 테마, 영업시간 등 인리치먼트가 필요한 장소만 핀포인트로 가져오기
    @Query("SELECT p FROM Place p WHERE p.theme IS NULL OR p.theme = '' OR p.placeType IS NULL OR p.placeType = '' OR p.recommendedDuration IS NULL OR p.openingHours IS NULL OR p.openingHours LIKE '%없음%' OR p.openingHours = ''")
    List<Place> findPlacesNeedingEnrichment(Pageable pageable);

    // 카테고리 정제가 필요한 장소만 핀포인트로 가져오기
    @Query("SELECT p FROM Place p WHERE p.category IS NULL OR p.category = ''")
    List<Place> findPlacesNeedingCategory(Pageable pageable);
}