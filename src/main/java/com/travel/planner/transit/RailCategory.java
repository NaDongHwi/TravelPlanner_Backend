package com.travel.planner.transit;

/** 교통수단 종류. 요금표와 평균 속도를 고르는 기준이다. (BUS·FERRY 는 GTFS 에서 가져온 노선에만 쓴다) */
public enum RailCategory {
    SHINKANSEN("신칸센"),
    JR("JR"),
    SUBWAY("지하철"),
    PRIVATE("사철"),
    THIRD_SECTOR("지방 철도"),
    TRAM("노면전차"),
    GUIDED("모노레일·신교통"),
    CABLE("케이블카"),
    BUS("버스"),
    FERRY("배");

    public final String label;

    RailCategory(String label) {
        this.label = label;
    }
}
