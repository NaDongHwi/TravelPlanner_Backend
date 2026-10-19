package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.service.PlanService.SimulatedItinerary.Type;
import com.travel.planner.util.PlaceKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 식당이 모자라 "자유 식사"가 된 자리를 채우는지 */
class MealGapTest {

    private final PlanService planService = new PlanService();

    @Test
    void restaurantTargetPerCity() {
        assertEquals(8, PlanGenerationService.restaurantTarget(2, 1, 2), "최소 8곳");
        assertEquals(16, PlanGenerationService.restaurantTarget(4, 1, 2));
        assertEquals(12, PlanGenerationService.restaurantTarget(4, 2, 3), "도시 2개면 도시마다 2일 × 3끼 × 2");
    }

    @Test
    void anchorsAreThePlaceBeforeEachFreeMealAndNearbyOnesAreMerged() {
        Place temple = TestPlaces.place("T", "절", "오사카", 34.70, 135.50, "관광지", "문화", TestPlaces.H_9_17);
        Place park = TestPlaces.place("P", "공원", "오사카", 34.75, 135.55, "관광지", "자연", TestPlaces.H_24);
        PlanService.DayPlan day = new PlanService.DayPlan(1, LocalDate.of(2026, 11, 3), "오사카");
        day.getItems().add(new SimulatedItinerary(Type.VISIT, temple, null, 600, 660, 20, 34.70, 135.50));
        day.getItems().add(new SimulatedItinerary(Type.MEAL, null, "점심 식사 (주변 자유 식사)", 690, 750, 0, null, null));
        day.getItems().add(new SimulatedItinerary(Type.VISIT, park, null, 800, 860, 30, 34.75, 135.55));
        day.getItems().add(new SimulatedItinerary(Type.MEAL, null, "저녁 식사 (주변 자유 식사)", 1080, 1140, 0, null, null));
        PlanService.DayPlan day2 = new PlanService.DayPlan(2, LocalDate.of(2026, 11, 4), "오사카");
        day2.getItems().add(new SimulatedItinerary(Type.VISIT, temple, null, 600, 660, 20, 34.7001, 135.5001));
        day2.getItems().add(new SimulatedItinerary(Type.MEAL, null, "점심 식사 (주변 자유 식사)", 690, 750, 0, null, null));

        List<Object[]> anchors = PlanGenerationService.mealGapAnchors(List.of(day, day2), List.of("오사카", "오사카"), "오사카");
        assertEquals(2, anchors.size(), "2일차 점심 자리는 1일차 절과 같은 곳이라 한 번만 찾는다");
        assertEquals(34.70, (double) anchors.get(0)[0], 1e-9);
        assertEquals(34.75, (double) anchors.get(1)[0], 1e-9);
        assertEquals("오사카", anchors.get(1)[2]);
    }

    @Test
    void freeMealsAreReplacedByRestaurantsFoundNearby() {
        PlanService.TripInput in = PlanServiceTest.osakaTrip(2, "오전", "오후", "문화", "사진");
        // 식당이 하나도 없는 후보 → 점심·저녁이 모두 자유 식사
        in.candidates = in.candidates.stream().filter(p -> PlaceKind.of(p) != PlaceKind.RESTAURANT && PlaceKind.of(p) != PlaceKind.BAR)
                .collect(Collectors.toCollection(ArrayList::new));
        PlanService.TripPlan before = planService.planTrip(in);
        int gaps = PlanGenerationService.mealGapCount(before);
        assertTrue(gaps >= 2, "식당이 없으면 자유 식사: " + gaps);

        // 구글: 찾은 자리 옆에 점심·저녁 영업하는 식당을 하나씩 돌려준다
        List<double[]> asked = new ArrayList<>();
        GoogleMapsService google = new GoogleMapsService(null) {
            int n = 0;
            @Override
            public List<Place> searchRestaurantsNear(double lat, double lng, double radiusMeters, String city) {
                asked.add(new double[]{lat, lng});
                List<Place> out = new ArrayList<>();
                for (int k = 0; k < 3; k++) {
                    Place r = TestPlaces.place("NEAR_" + (n++), "근처 식당 " + n, city, lat + 0.001 * (k + 1), lng, "식음", "맛집", TestPlaces.H_RESTAURANT);
                    r.setRating(4.2);
                    r.setUserRatingCount(300);
                    out.add(r);
                }
                return out;
            }
        };
        PlanPersistenceService persistence = new PlanPersistenceService(null, null, null, null) {
            @Override public Place upsertPlace(Place fetched) { return fetched; }
        };
        PlanGenerationService service = new PlanGenerationService(planService, new PlanValidationService(null),
                persistence, null, null, null, google, null, null, null, null, null, null, null);

        PlanService.TripPlan after = service.fillMealGaps(in, before, null, "오사카");
        assertTrue(!asked.isEmpty() && asked.size() <= PlanGenerationService.MAX_MEAL_GAP_SEARCHES, "찾은 횟수 " + asked.size());
        assertTrue(PlanGenerationService.mealGapCount(after) < gaps, "자유 식사 " + gaps + " → " + PlanGenerationService.mealGapCount(after));
        assertTrue(after.getDays().stream().flatMap(d -> d.getItems().stream())
                .anyMatch(i -> i.getPlace() != null && i.getPlace().getPlaceId().startsWith("NEAR_")), "찾은 식당이 일정에 들어간다");

        // 자유 식사가 없는 일정이면 아무것도 찾지 않는다
        asked.clear();
        PlanService.TripPlan full = planService.planTrip(PlanServiceTest.osakaTrip(2, "오전", "오후", "맛집"));
        if (PlanGenerationService.mealGapCount(full) == 0) {
            assertTrue(service.fillMealGaps(in, full, null, "오사카") == full);
            assertTrue(asked.isEmpty());
        }
    }
}
