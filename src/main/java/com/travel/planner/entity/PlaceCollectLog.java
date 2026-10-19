package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 지역별 장소 수집(/admin/collect-region)에서 끝까지 다 돈 도시 기록.
 * 다음에 같은 지역을 다시 돌리면 여기 있는 도시는 건너뛴다 (force=true 면 다시 돈다).
 * 한도에 걸려 중간에 멈춘 도시는 적지 않으므로, 다음 실행 때 그 도시부터 이어서 한다.
 */
@Entity
@Table(name = "place_collect_log")
@Getter
@Setter
public class PlaceCollectLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String city;

    private String region;

    /** FULL(큰 도시: 검색어 16개 × 격자 5곳) 또는 LIGHT(작은 도시: 검색어 8개 × 도심 1곳) */
    private String mode;

    /** 이 도시에 쓴 장소 검색(Text Search) 호출 수 */
    private Integer calls;

    /** 새로 넣은 장소 수 */
    private Integer inserted;

    @Column(nullable = false)
    private LocalDateTime finishedAt = LocalDateTime.now();
}
