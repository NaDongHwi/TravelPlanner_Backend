package com.travel.planner.lodging;

import java.time.LocalDate;

/** 라쿠텐 트래블 API 호출 (응답 JSON 원문을 돌려준다. 찾은 것이 없으면 빈 hotels, 실패하면 null) */
public interface RakutenTravelApi {

    /** 키가 설정되어 있어 호출할 수 있는가 */
    boolean configured();

    /** 좌표 주변의 숙소 (날짜 무관, 표시 최저가 포함) */
    String simpleSearch(double latitude, double longitude, double radiusKm);

    /** 그 숙소의 그 날짜·인원 빈방 요금 (싼 순) */
    String vacantSearch(long hotelNo, LocalDate checkIn, LocalDate checkOut, int adults, int rooms);
}
