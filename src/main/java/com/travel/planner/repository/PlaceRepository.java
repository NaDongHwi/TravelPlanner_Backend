package com.travel.planner.repository;

import com.travel.planner.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PlaceRepository extends JpaRepository<Place, Long> {
    // 💡 JpaRepository를 상속받는 순간, "DB에 저장해(save)", "다 가져와(findAll)" 같은 명령어들을 자동으로 씁니다.
    List<Place> findByCity(String city);
    boolean existsByPlaceId(String placeId);
}