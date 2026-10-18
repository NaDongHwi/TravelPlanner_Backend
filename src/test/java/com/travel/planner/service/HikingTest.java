package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.service.PlanService.TripInput;
import com.travel.planner.service.PlanService.TripPlan;
import com.travel.planner.util.Hiking;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 등산하는 곳(산 정상 등): 그대로 추천하되, 아이·어르신과 함께면 점수를 낮춘다 */
class HikingTest {

    private final PlanService planService = new PlanService();

    @Test
    void recognisesHikingSpots() {
        Place summit = place("浜石岳", "산", "후지산과 스루가만을 360도 파노라마로 조망하는 산 정상");
        Place trail = place("Some Trail", "공원", "정상까지 이어지는 등산로가 있는 숲");
        Place nameOnly = place("テスト岳", null, null);
        Place park = place("Kiyomizuyama Park", "공원", "숲과 폭포, 하이킹 길을 따라 자연을 즐기는 도심 공원");
        Place temple = place("成田山新勝寺", "사찰", "성전과 탑이 늘어선 큰 절");
        assertTrue(Hiking.isHike(summit));
        assertTrue(Hiking.isHike(trail));
        assertTrue(Hiking.isHike(nameOnly));
        assertFalse(Hiking.isHike(park), "산책로가 있는 도심 공원은 등산이 아니다");
        assertFalse(Hiking.isHike(temple));
    }

    @Test
    void summitIsChosenWithFriendsButYieldsToOtherSightsWithFamily() {
        TripInput friends = tripWithSummit("친구");
        TripInput parents = tripWithSummit("부모님");
        assertTrue(contains(planService.planTrip(friends), "テスト山頂展望"), "친구 여행에서는 인기 있는 산 정상을 넣는다");
        assertFalse(contains(planService.planTrip(parents), "テスト山頂展望"), "부모님과 함께면 다른 볼거리를 먼저 고른다");
        assertTrue(PlanService.withChildrenOrElderly(parents.request));
        assertFalse(PlanService.withChildrenOrElderly(friends.request));
    }

    private static TripInput tripWithSummit(String companion) {
        TripInput in = PlanServiceTest.osakaTrip(2, "오전", "오후", "자연", "사진");
        in.request.setCompanion(companion);
        // 도심 가까이에 있는, 평점·리뷰가 아주 좋은 산 정상
        Place summit = TestPlaces.place("SUMMIT", "テスト山頂展望", "오사카", 34.6900, 135.5200, "관광지", "자연,사진", TestPlaces.H_24);
        summit.setSubType("산 정상");
        summit.setSummary("도시와 바다를 내려다보는 산 정상");
        summit.setRating(4.9);
        summit.setUserRatingCount(30000);
        in.candidates.add(summit);
        return in;
    }

    private static boolean contains(TripPlan plan, String name) {
        return plan.getDays().stream().flatMap(d -> d.getItems().stream())
                .anyMatch(i -> i.getType() == SimulatedItinerary.Type.VISIT && i.getPlace() != null && name.equals(i.getPlace().getName()));
    }

    private static Place place(String name, String subType, String summary) {
        Place p = new Place();
        p.setName(name);
        p.setSubType(subType);
        p.setSummary(summary);
        return p;
    }
}
