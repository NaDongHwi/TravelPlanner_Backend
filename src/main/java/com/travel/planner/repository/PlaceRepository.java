package com.travel.planner.repository;

import com.travel.planner.entity.Place;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlaceRepository extends JpaRepository<Place, Long> {

    List<Place> findByCity(String city);

    List<Place> findByCityIn(List<String> cities);

    List<Place> findTop10ByCityAndCategory(String city, String category);

    boolean existsByPlaceId(String placeId);

    java.util.Optional<Place> findByPlaceId(String placeId);

    // 테마, 영업시간 등 인리치먼트가 필요한 장소만 핀포인트로 가져오기.
    //  - 영업시간은 "한 번도 조회 안 한 것"(NULL / 빈 값 / 예전 기본 문구)만 대상이다.
    //    구글에 조회했는데 영업시간이 없던 곳은 "확인 불가"로 표시되어 여기 걸리지 않는다.
    //    (이전의 LIKE '%없음%' 조건은 거리·공원처럼 원래 영업시간이 없는 곳을 실행할 때마다 다시 조회했다)
    //  - 숙소·교통과 더미 장소는 테마가 필요 없으므로 제외한다.
    @Query("SELECT p FROM Place p WHERE (p.category IS NULL OR p.category NOT IN ('숙소', '교통', '자유시간', '식사', '고정일정')) "
            + "AND (p.theme IS NULL OR p.theme = '' OR p.placeType IS NULL OR p.placeType = '' OR p.recommendedDuration IS NULL "
            + "OR p.openingHours IS NULL OR p.openingHours = '' OR p.openingHours = '영업시간 정보 없음') ORDER BY p.id")
    List<Place> findPlacesNeedingEnrichment(Pageable pageable);

    // 카테고리 정제가 필요한 장소만 핀포인트로 가져오기
    @Query("SELECT p FROM Place p WHERE p.category IS NULL OR p.category = '' ORDER BY p.id")
    List<Place> findPlacesNeedingCategory(Pageable pageable);

    // 30일 갱신 배치 대상: 오래된 순으로 일부만 (한 번에 전부 재조회하지 않도록 페이지로 끊는다)
    @Query("SELECT p FROM Place p WHERE p.placeId NOT LIKE 'DUMMY%' AND p.placeId NOT LIKE 'AIRPORT%' "
            + "AND (p.lastUpdated IS NULL OR p.lastUpdated < :threshold) ORDER BY p.lastUpdated ASC")
    List<Place> findRefreshTargets(@Param("threshold") java.time.LocalDateTime threshold, Pageable pageable);

    List<Place> findByNameContaining(String keyword);
}
