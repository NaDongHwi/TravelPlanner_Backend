package com.travel.planner.service;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.entity.Place;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.util.OpeningHours;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 엔진 결과(SimulatedItinerary) → 응답 DTO(TimelineItem) 변환.
 *
 * 주소·전화·영업시간은 DB 에 저장된 값을 쓴다. 이전에는 타임라인 항목마다 Place Details 를 실시간으로
 * 불러서(4일 일정이면 30회 이상) 응답이 느리고 과금이 컸다. 예전에 수집해 주소가 비어 있는 장소만
 * 요청당 정해진 횟수 안에서 한 번 조회해 DB 에 채워 넣는다.
 */
@Component
@RequiredArgsConstructor
public class TimelineAssembler {

    private static final int MAX_DETAIL_CALLS_PER_REQUEST = 12;

    private final GoogleMapsService googleMapsService;
    private final PlaceRepository placeRepository;

    public List<AiRouteResponse.TimelineItem> toTimeline(List<PlanService.DayPlan> days, String lang) {
        int[] budget = {MAX_DETAIL_CALLS_PER_REQUEST};
        List<AiRouteResponse.TimelineItem> timeline = new ArrayList<>();
        for (PlanService.DayPlan day : days) {
            timeline.addAll(toTimeline(day.getDayNumber(), day.getDate(), day.getItems(), lang, budget));
        }
        return timeline;
    }

    public List<AiRouteResponse.TimelineItem> toTimeline(int dayNumber, LocalDate date, List<SimulatedItinerary> items, String lang) {
        return toTimeline(dayNumber, date, items, lang, new int[]{MAX_DETAIL_CALLS_PER_REQUEST});
    }

    private List<AiRouteResponse.TimelineItem> toTimeline(int dayNumber, LocalDate date, List<SimulatedItinerary> items,
                                                          String lang, int[] budget) {
        boolean korean = lang == null || lang.isBlank() || lang.toLowerCase().startsWith("ko");
        List<AiRouteResponse.TimelineItem> timeline = new ArrayList<>();

        for (SimulatedItinerary sim : items) {
            AiRouteResponse.TimelineItem item = new AiRouteResponse.TimelineItem();
            item.setDay(dayNumber);
            item.setTime(sim.getTime());
            item.setEndTime(sim.getEndTime());
            item.setTravelMinutes(sim.getTravelMinutes());
            item.setPlaceName(sim.getDisplayName());
            item.setCategory(sim.getCategory());
            item.setDescription(describe(sim));
            item.setLatitude(sim.getLatitude());
            item.setLongitude(sim.getLongitude());
            item.setFormattedAddress("주소 정보 없음");
            item.setPhoneNumber("전화번호 정보 없음");
            item.setOpeningHours(OpeningHours.LEGACY_DEFAULT);

            Place place = sim.getPlace();
            if (place != null) {
                item.setPlaceId(place.getPlaceId());
                Place source = place;

                if (!korean && budget[0] > 0) {
                    // 한국어 외 언어는 저장된 값(한국어)을 쓸 수 없으므로 그 언어로 조회한다 (DB 에는 저장하지 않음)
                    budget[0]--;
                    Place localized = googleMapsService.getPlaceDetailsById(place.getPlaceId(), lang);
                    if (localized != null) source = localized;
                } else if (korean && place.getAddress() == null && budget[0] > 0) {
                    budget[0]--;
                    if (googleMapsService.refreshPlace(place, "ko") && place.getId() != null) {
                        placeRepository.save(place);
                    }
                }

                if (source.getAddress() != null) item.setFormattedAddress(source.getAddress());
                if (source.getPhone() != null) item.setPhoneNumber(source.getPhone());
                item.setOpeningHours(OpeningHours.describe(source, date));
            }
            timeline.add(item);
        }
        return timeline;
    }

    public static String describe(SimulatedItinerary sim) {
        Place place = sim.getPlace();
        switch (sim.getType()) {
            case START:
                return PlanService.isAirport(place) ? "입국 수속 후 이동 시작" : "숙소 출발";
            case END:
                return PlanService.isAirport(place) ? "출국 수속 (출국편 2시간 전까지 도착)" : "숙소 도착 및 휴식";
            case FREE:
                return "자유 시간";
            case MEAL:
                return "근처에서 자유롭게 식사";
            case FIXED:
                return "사용자 고정 일정";
            default:
                return place != null && place.getTheme() != null && !place.getTheme().isBlank()
                        ? place.getTheme() + " 일정" : "추천 일정";
        }
    }
}
