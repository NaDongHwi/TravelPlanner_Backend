package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * GTFS 노선의 운행 패턴 하나: "이 노선이 이 정류장들을 이 순서로 지난다".
 * 같은 노선이라도 방향·경유지·구간 운행에 따라 패턴이 여러 개다.
 * 시각표 원본 대신 구간 평균 소요 시간, 요일별 운행 횟수, 구간 요금표만 저장한다.
 */
@Entity
@Getter
@Setter
@Table(name = "transit_pattern")
public class TransitPattern {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String feedKey;

    @Column(length = 120)
    private String routeCode;        // 원본의 route_id

    @Column(nullable = false, length = 200)
    private String routeName;        // 계통 번호·노선명 (일본어 원문)

    @Column(length = 300)
    private String routeLongName;

    @Column(length = 200)
    private String agency;           // 운영사 (일본어 원문)

    @Column(nullable = false, length = 20)
    private String category;         // BUS / TRAM / FERRY / SUBWAY / …

    @Column(length = 300)
    private String headsign;         // 행선지 표시 (일본어 원문)

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String stopIds;          // 지나는 정류장의 transit_stop.id, 쉼표로 구분

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String cumMinutes;       // 첫 정류장 출발부터 각 정류장까지의 평균 시간(0.1분 단위 정수), 쉼표로 구분

    @Column(columnDefinition = "MEDIUMTEXT")
    private String stopFlags;        // 정류장별 제한 (1 = 승차 불가, 2 = 하차 불가). 제한이 없으면 null

    private Integer tripsWeekday;    // 하루 운행 횟수
    private Integer tripsSaturday;
    private Integer tripsHoliday;

    private Integer firstDeparture;  // 첫차·막차가 첫 정류장을 떠나는 시각 (0시부터의 분)
    private Integer lastDeparture;

    private LocalDate serviceFrom;   // 계절 노선의 운행 기간. 연중 운행이면 null
    private LocalDate serviceTo;

    private Boolean irregular;       // 매일 다니지 않는 노선

    private Integer flatFare;        // 균일 요금이면 그 값

    @Column(columnDefinition = "MEDIUMTEXT")
    private String fareTable;        // 구간 요금표 (i 번째에서 타서 j 번째에서 내릴 때, i<j 순서로 쉼표 구분. 모르면 -1). 균일 요금이면 null
}
