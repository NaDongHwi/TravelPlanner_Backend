package com.travel.planner.service;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.dto.LodgingCostDto;
import com.travel.planner.entity.Accommodation;
import com.travel.planner.lodging.RakutenHotels;
import com.travel.planner.lodging.RakutenTravelApi;
import com.travel.planner.util.DistanceUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 숙박비.
 *  1. 사용자가 숙소를 직접 입력하면서 금액(priceYen, 그 숙소의 숙박 합계)을 적었으면 그 값.
 *  2. 아니면 라쿠텐 트래블에서 그 숙소를 찾아(좌표로 가장 가까운 숙소, 100m 이내)
 *     - 날짜를 정한 일정: 그 날짜·인원으로 예약 가능한 요금 가운데 가장 싼 것 (첫날 밤 요금 × 박 수)
 *     - 날짜 미정 일정: 숙소가 내건 1실 1박 최저가(목안) × 방 수 × 박 수
 *  3. 못 찾으면 비워 둔다. 추정값을 지어내지 않는다.
 * 금액은 모두 일행 전체 기준이다. 1인 금액은 인원으로 나눈다.
 */
@Service
@RequiredArgsConstructor
public class LodgingCostService {

    /** 구글에서 찾은 숙소와 라쿠텐 숙소를 같은 곳으로 볼 최대 거리 */
    static final double MATCH_METERS = 100;
    /** 일정 하나에서 라쿠텐에 묻는 숙소 수 (호출 사이를 1초씩 띄우므로 너무 많으면 응답이 늦어진다) */
    static final int MAX_LOOKUPS = 20;
    /** 이보다 긴 숙박은 빈방 조회를 첫날 밤만 한다 */
    static final int LONG_STAY_NIGHTS = 7;   // 장기 투어는 숙소가 도시마다 바뀐다 (라쿠텐 1초 1회 규칙 때문에 한 곳에 1~2초)

    private final RakutenTravelApi rakuten;

    /** 구글 place_id → 라쿠텐 숙소 (한 번 찾은 숙소는 다시 찾지 않는다. 서버를 다시 켜면 비워진다) */
    private final Map<String, RakutenHotels.Hotel> matched = new ConcurrentHashMap<>();

    /** 결과 */
    public static final class Result {
        public final List<LodgingCostDto> lodgings = new ArrayList<>();
        public Integer totalYen;          // 금액을 아는 숙소의 합계 (하나도 모르면 null)
        public boolean complete;          // 모든 숙소의 금액을 알았는가
        public String note;
    }

    /**
     * @param stays    숙소별 숙박 구간 (체크인·체크아웃 날짜. 날짜 미정 일정이면 자리 표시 날짜)
     * @param baseDate 1일차 날짜 (날짜 미정 일정이면 자리 표시 날짜)
     */
    public Result estimate(List<Accommodation> stays, LocalDate baseDate, boolean undated, int adults) {
        Result result = new Result();
        if (stays == null || stays.isEmpty()) {
            result.complete = true;
            return result;
        }
        int rooms = roomsFor(adults);
        int lookups = 0;
        int unknown = 0;
        int sum = 0;
        boolean any = false;
        for (Accommodation stay : stays) {
            LodgingCostDto dto = new LodgingCostDto();
            int nights = (int) Math.max(0, ChronoUnit.DAYS.between(stay.getCheckIn(), stay.getCheckOut()));
            dto.setName(stay.getName());
            dto.setCheckInDay((int) ChronoUnit.DAYS.between(baseDate, stay.getCheckIn()) + 1);
            dto.setCheckOutDay((int) ChronoUnit.DAYS.between(baseDate, stay.getCheckOut()) + 1);
            if (!undated) {
                dto.setCheckIn(stay.getCheckIn().toString());
                dto.setCheckOut(stay.getCheckOut().toString());
            }
            dto.setNights(nights);

            if (stay.getPriceYen() != null) {
                setTotal(dto, stay.getPriceYen(), nights);
                dto.setSource("user");
                dto.setBasis("직접 입력한 숙박비");
            } else if (nights == 0) {
                setTotal(dto, 0, 0);
                dto.setBasis("숙박 없음");
            } else if (!rakuten.configured()) {
                dto.setBasis("숙박비 조회가 설정되지 않았습니다");
            } else if (stay.getLatitude() == null || stay.getLongitude() == null) {
                dto.setBasis("숙소 위치를 몰라 숙박비를 찾지 못했습니다");
            } else if (lookups >= MAX_LOOKUPS) {
                dto.setBasis("숙소가 많아 숙박비 조회를 " + MAX_LOOKUPS + "곳까지만 했습니다");
            } else {
                lookups++;
                try {
                    lookup(dto, stay, undated, adults, rooms, nights);
                } catch (Exception e) {
                    System.out.println("[숙박비] " + stay.getName() + " 조회 중 오류: " + e.getMessage());
                    dto.setBasis("숙박비를 조회하지 못했습니다");
                }
            }

            if (dto.getTotalYen() != null) {
                sum += dto.getTotalYen();
                any = true;
                stay.setPriceYen(dto.getTotalYen());
                stay.setPriceSource(dto.getSource());
            } else {
                unknown++;
            }
            result.lodgings.add(dto);
        }
        result.totalYen = any ? sum : null;
        result.complete = unknown == 0;
        result.note = note(result, undated, adults, unknown);
        return result;
    }

    private void lookup(LodgingCostDto dto, Accommodation stay, boolean undated, int adults, int rooms, int nights) {
        RakutenHotels.Hotel hotel = match(stay);
        if (hotel == null) {
            dto.setBasis("라쿠텐 트래블에서 이 숙소를 찾지 못했습니다");
            return;
        }
        dto.setMatchedHotelName(hotel.name);
        dto.setUrl(hotel.url);

        if (!undated) {
            // 긴 숙박(LONG_STAY_NIGHTS 박 초과, 예: 한 달 살아보기)은 첫날 밤만 조회한다. 쓰는 값이 첫날 밤 요금이고,
            // 한 달 전체를 빈방 조건으로 걸면 라쿠텐이 받지 않거나 빈방이 없다고 답할 수 있다.
            java.time.LocalDate checkOut = nights > LONG_STAY_NIGHTS ? stay.getCheckIn().plusDays(1) : stay.getCheckOut();
            List<RakutenHotels.Hotel> vacant = RakutenHotels.parse(
                    rakuten.vacantSearch(hotel.hotelNo, stay.getCheckIn(), checkOut, adults, rooms));
            Integer night = vacant.stream().filter(h -> h.hotelNo == hotel.hotelNo && h.lowestNightTotal != null)
                    .map(h -> h.lowestNightTotal).min(Integer::compare).orElse(null);
            if (night != null) {
                setTotal(dto, night * nights, nights);
                dto.setPerNightYen(night);
                dto.setSource("rakuten");
                dto.setBasis("그 날짜에 " + adults + "명이 예약할 수 있는 가장 싼 요금" + (nights > 1 ? " (첫날 밤 요금 × " + nights + "박)" : "")
                        + (nights > LONG_STAY_NIGHTS ? ". 긴 숙박이라 첫날 밤 빈방만 확인했고, 장기 숙박 할인은 반영하지 않았습니다" : ""));
                return;
            }
            if (hotel.minCharge != null) {
                setTotal(dto, hotel.minCharge * rooms * nights, nights);
                dto.setSource("rakuten-estimate");
                dto.setBasis("그 날짜에 예약 가능한 방을 찾지 못해 숙소의 표시 최저가(1실 1박)로 어림했습니다. 만실일 수 있습니다");
                return;
            }
            dto.setBasis("그 날짜에 예약 가능한 방과 요금 정보를 찾지 못했습니다");
            return;
        }
        if (hotel.minCharge != null) {
            setTotal(dto, hotel.minCharge * rooms * nights, nights);
            dto.setSource("rakuten-estimate");
            dto.setBasis("날짜가 정해지지 않아 숙소의 표시 최저가(1실 1박) × " + rooms + "실 × " + nights + "박으로 어림했습니다. 날짜에 따라 크게 달라집니다");
            return;
        }
        dto.setBasis("숙소의 요금 정보가 없습니다");
    }

    /** 좌표로 가장 가까운 라쿠텐 숙소 (MATCH_METERS 이내) */
    RakutenHotels.Hotel match(Accommodation stay) {
        String key = stay.getPlaceId();
        if (key != null && matched.containsKey(key)) return matched.get(key);
        List<RakutenHotels.Hotel> near = RakutenHotels.parse(rakuten.simpleSearch(stay.getLatitude(), stay.getLongitude(), 0.3));
        RakutenHotels.Hotel best = null;
        double bestMeters = Double.MAX_VALUE;
        for (RakutenHotels.Hotel h : near) {
            if (Double.isNaN(h.latitude) || Double.isNaN(h.longitude)) continue;
            double meters = DistanceUtil.calculateDistance(stay.getLatitude(), stay.getLongitude(), h.latitude, h.longitude) * 1000;
            if (meters < bestMeters) {
                bestMeters = meters;
                best = h;
            }
        }
        if (best == null || bestMeters > MATCH_METERS) return null;
        if (key != null) matched.put(key, best);
        return best;
    }

    static int roomsFor(int adults) {
        return adults <= 2 ? 1 : (adults + 1) / 2;
    }

    private static void setTotal(LodgingCostDto dto, int total, int nights) {
        dto.setTotalYen(total);
        if (nights > 0) dto.setPerNightYen(Math.round((float) total / nights));
    }

    private static String note(Result r, boolean undated, int adults, int unknown) {
        StringBuilder sb = new StringBuilder("숙박비는 " + adults + "명 일행 전체 기준입니다.");
        boolean fromRakuten = r.lodgings.stream().anyMatch(l -> l.getSource() != null && l.getSource().startsWith("rakuten"));
        if (fromRakuten) {
            sb.append(" 라쿠텐 트래블의 가장 싼 방 요금이라 실제로 고를 방(조식·방 크기·취소 조건)에 따라 더 비쌀 수 있습니다.");
            if (undated) sb.append(" 날짜가 정해지지 않아 숙소의 표시 최저가로 어림했습니다.");
        }
        if (unknown > 0) sb.append(" 숙소 ").append(unknown).append("곳은 숙박비를 알 수 없어 합계에서 뺐습니다.");
        return sb.toString();
    }

    /** 응답에 숙박비와 1인 경비 합계를 붙인다 (교통비는 1인 기준이므로 숙박비를 인원으로 나눠 더한다) */
    public static void apply(AiRouteResponse response, Result result, int adults) {
        response.setAdults(adults);
        response.setLodgings(result.lodgings);
        response.setLodgingCostYen(result.totalYen);
        response.setLodgingCostComplete(result.complete);
        Integer transit = response.getTransitFareWithPassesYen() != null ? response.getTransitFareWithPassesYen() : response.getTransitFareYen();
        if (transit != null || result.totalYen != null) {
            int lodgingPerPerson = result.totalYen == null ? 0 : Math.round((float) result.totalYen / Math.max(1, adults));
            response.setTripCostPerPersonYen((transit == null ? 0 : transit) + lodgingPerPerson);
        }
        StringBuilder sb = new StringBuilder("1인 경비 = 교통비(성인 1인");
        if (response.getTransitFareWithPassesYen() != null) sb.append(", 추천 패스 사용");
        sb.append(") + 숙박비 ÷ ").append(adults).append("명. 식비·입장료는 들어 있지 않습니다.");
        if (result.note != null && !result.lodgings.isEmpty()) sb.append(' ').append(result.note);
        response.setCostNote(sb.toString());
    }
}
