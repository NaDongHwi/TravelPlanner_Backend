package com.travel.planner.service;

import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.entity.Place;
import com.travel.planner.repository.TransitFeedRepository;
import com.travel.planner.repository.TransitLinkRepository;
import com.travel.planner.repository.TransitNameRepository;
import com.travel.planner.repository.TransitPatternRepository;
import com.travel.planner.repository.TransitStationRepository;
import com.travel.planner.repository.TransitStopRepository;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.service.PlanService.SimulatedItinerary.Type;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 위치가 없는 줄(숙소 근처 저녁 등)로 쓴 이동 시간이 다음 장소까지의 이동에 더해지는지 ("약 0분" 버그) */
class TransitLegCarryTest {

    @Test
    void travelSpentOnUnlocatedMealIsCarriedToTheNextPlace() {
        Place cafe = place("카페", "식음");
        Place hotel = place("호텔", "숙소");
        List<SimulatedItinerary> items = new ArrayList<>();
        items.add(new SimulatedItinerary(Type.VISIT, cafe, null, 17 * 60 + 45, 19 * 60, 45, 35.0397, 138.4807));
        // "숙소 근처에서 저녁" — 위치 없이 이동 45분을 썼다
        items.add(new SimulatedItinerary(Type.MEAL, null, "저녁 식사 (숙소 근처)", 19 * 60 + 45, 20 * 60 + 45, 45, null, null));
        // 숙소 도착은 이동 0분으로 잡혀 있다
        items.add(new SimulatedItinerary(Type.END, hotel, null, 20 * 60 + 45, null, 0, 34.9730, 138.3899));

        List<TransitLegDto> legs = routes().legsFor(items, "도보 및 대중교통");

        assertNull(legs.get(1), "위치 없는 줄에는 이동 정보가 없다");
        TransitLegDto toHotel = legs.get(2);
        assertEquals(45, toHotel.getMinutes(), "카페에서 숙소까지 45분 (이전에는 0분)");
        assertTrue(toHotel.getSummary().contains("45분"), toHotel.getSummary());
    }

    private static Place place(String name, String category) {
        Place p = new Place();
        p.setName(name);
        p.setCategory(category);
        return p;
    }

    private static RailRouteService routes() {
        TransitNameService names = new TransitNameService(empty(TransitNameRepository.class), empty(TransitStationRepository.class),
                empty(TransitStopRepository.class), empty(TransitPatternRepository.class), null);
        return new RailRouteService(empty(TransitStationRepository.class), empty(TransitLinkRepository.class), empty(TransitStopRepository.class),
                empty(TransitPatternRepository.class), empty(TransitFeedRepository.class), names, FareMemory.emptyService(), new BusStopService());
    }

    @SuppressWarnings("unchecked")
    private static <T> T empty(Class<T> type) {
        return (T) Proxy.newProxyInstance(TransitLegCarryTest.class.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "findAll": return new ArrayList<>();
                case "findByPlaceId": return Optional.empty();
                default: return null;
            }
        });
    }
}
