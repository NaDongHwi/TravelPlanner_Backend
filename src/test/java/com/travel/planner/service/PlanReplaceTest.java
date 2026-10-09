package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.service.PlanService.DayContext;
import com.travel.planner.service.PlanService.DayItem;
import com.travel.planner.service.PlanService.DayPlan;
import com.travel.planner.service.PlanService.ReplaceResult;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.service.PlanService.SimulatedItinerary.Type;
import com.travel.planner.service.PlanService.TripInput;
import com.travel.planner.service.PlanService.TripPlan;
import com.travel.planner.util.OpeningHours;
import com.travel.planner.util.PlaceKind;
import com.travel.planner.util.TimeUtil;
import com.travel.planner.util.TravelTimeEstimator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "고른 일정만 바꾸기" 테스트.
 * 핵심 약속: 고르지 않은 줄은 장소도 시각도 그대로이고, 새 장소는 앞뒤 일정 사이에 이동 시간까지 포함해 들어간다.
 */
class PlanReplaceTest {

    private final PlanService planService = new PlanService();

    @Test
    void replacingOneSightLeavesEveryOtherLineUntouched() {
        Fixture f = fixture(PlanServiceTest.osakaTrip(4, "오전", "오후", "문화", "자연"), 2);
        int index = firstIndex(f.day, false);
        Place old = f.day.getItems().get(index).getPlace();

        ReplaceResult result = f.replace(null, new HashSet<>(), index);

        assertEquals(1, result.replacements.size());
        assertTrue(result.replacements.get(0).changed, result.replacements.get(0).message);
        Place fresh = result.replacements.get(0).newPlace;
        assertNotNull(fresh);
        assertFalse(f.tripIds.contains(fresh.getPlaceId()), "이미 일정에 있는 장소로 바꾸면 안 된다");
        assertFalse(PlaceKind.of(fresh).isFood(), "관광지 자리는 관광지로 바꾼다");
        assertEquals(old.getName(), result.replacements.get(0).oldName);

        assertOthersUntouched(f, result, index);
        assertFeasible(f, result);
    }

    @Test
    void mealIsReplacedByAnotherRestaurant() {
        Fixture f = fixture(PlanServiceTest.osakaTrip(4, "오전", "오후", "문화", "자연"), 2);
        int index = firstIndex(f.day, true);

        ReplaceResult result = f.replace("문화", new HashSet<>(), index);   // 식사 자리는 테마를 줘도 식당으로만 바꾼다

        assertTrue(result.replacements.get(0).changed, result.replacements.get(0).message);
        PlaceKind kind = PlaceKind.of(result.replacements.get(0).newPlace);
        assertTrue(kind == PlaceKind.RESTAURANT || kind == PlaceKind.BAR, "끼니가 사라지면 안 된다: " + kind);
        assertOthersUntouched(f, result, index);
        assertFeasible(f, result);
    }

    @Test
    void themeRestrictsTheReplacement() {
        Fixture f = fixture(PlanServiceTest.osakaTrip(4, "오전", "오후", "문화", "자연"), 2);
        int index = firstIndex(f.day, false);

        ReplaceResult themed = f.replace("사진", new HashSet<>(), index);
        if (themed.replacements.get(0).changed) {
            assertTrue(planService.themesOf(themed.replacements.get(0).newPlace).contains("사진"),
                    "요청한 테마의 장소여야 한다: " + themed.replacements.get(0).newPlace.getName());
            assertFeasible(f, themed);
        }

        // 그 테마 후보가 하나도 없으면 원래 일정을 그대로 두고 이유를 알려 준다
        Fixture g = fixture(PlanServiceTest.osakaTrip(4, "오전", "오후", "문화", "자연"), 2);
        g.in.candidates.removeIf(p -> !g.tripIds.contains(p.getPlaceId()) && planService.themesOf(p).contains("서브컬쳐"));
        int gi = firstIndex(g.day, false);
        ReplaceResult none = g.replace("서브컬쳐", new HashSet<>(), gi);
        assertFalse(none.replacements.get(0).changed);
        assertNull(none.replacements.get(0).newPlace);
        assertTrue(none.replacements.get(0).message.contains("그대로 두었습니다"), none.replacements.get(0).message);
        assertEquals(names(g.day.getItems()), names(none.items), "못 바꿨으면 하루 일정이 그대로여야 한다");
    }

    @Test
    void rejectedPlaceIsNotOfferedAgain() {
        Fixture f = fixture(PlanServiceTest.osakaTrip(4, "오전", "오후", "문화", "자연"), 2);
        int index = firstIndex(f.day, false);
        Place first = f.replace(null, new HashSet<>(), index).replacements.get(0).newPlace;
        assertNotNull(first);

        // 사용자가 그것도 싫다고 한 경우: 처음 추천한 곳을 '거절 목록'에 넣고 다시 요청
        Fixture again = fixture(PlanServiceTest.osakaTrip(4, "오전", "오후", "문화", "자연"), 2);
        Set<String> rejected = new HashSet<>();
        rejected.add(first.getPlaceId());
        PlanService.Replacement second = again.replace(null, rejected, index).replacements.get(0);
        if (second.changed) {
            assertFalse(first.getPlaceId().equals(second.newPlace.getPlaceId()), "거절한 장소가 다시 나오면 안 된다");
        }
    }

    @Test
    void severalLinesIncludingNeighboursCanBeReplacedTogether() {
        Fixture f = fixture(PlanServiceTest.osakaTrip(4, "오전", "오후", "관광", "사진"), 2);
        List<Integer> targets = new ArrayList<>();
        for (int i = 0; i < f.day.getItems().size(); i++) {
            if (f.day.getItems().get(i).getType() == Type.VISIT) targets.add(i);
        }
        assertTrue(targets.size() >= 3, "테스트 전제: 방문지가 3곳 이상");

        ReplaceResult result = f.replace(null, new HashSet<>(), targets.stream().mapToInt(Integer::intValue).toArray());

        assertEquals(targets.size(), result.replacements.size());
        assertTrue(result.replacements.stream().anyMatch(r -> r.changed), "한 곳도 못 바꾸면 테스트 데이터가 부족한 것");
        Set<String> fresh = new HashSet<>();
        for (PlanService.Replacement r : result.replacements) {
            if (!r.changed) continue;
            assertTrue(fresh.add(r.newPlace.getPlaceId()), "같은 장소를 두 자리에 넣으면 안 된다");
            assertFalse(f.tripIds.contains(r.newPlace.getPlaceId()));
        }
        assertFeasible(f, result);
    }

    @Test
    void anchorsCannotBeReplaced() {
        Fixture f = fixture(PlanServiceTest.osakaTrip(4, "오전", "오후", "문화", "자연"), 2);
        ReplaceResult result = f.replace(null, new HashSet<>(), 0);   // 숙소 출발
        assertFalse(result.replacements.get(0).changed);
        assertTrue(result.replacements.get(0).message.contains("바꿀 수 없습니다"));
        assertEquals(names(f.day.getItems()), names(result.items));
    }

    @Test
    void replacingTheLastVisitStillMeetsTheFlightDeadline() {
        // 마지막 날(오후 14:00 출국 → 12:00 까지 공항 도착)의 마지막 방문지를 바꿔도 공항 도착 마감을 지킨다
        Fixture f = fixture(PlanServiceTest.osakaTrip(4, "오전", "18:30", "문화", "자연"), 4);
        int index = -1;
        for (int i = 0; i < f.day.getItems().size(); i++) {
            if (f.day.getItems().get(i).getType() == Type.VISIT) index = i;
        }
        assertTrue(index > 0, "테스트 전제: 마지막 날에 방문지가 있어야 한다");

        ReplaceResult result = f.replace(null, new HashSet<>(), index);

        SimulatedItinerary end = result.items.get(result.items.size() - 1);
        assertEquals(Type.END, end.getType());
        assertTrue(end.getStartMin() <= 18 * 60 + 30 - 120, "공항 도착 " + end.getTime() + " 이 수속 마감(16:30)보다 늦다");
        assertFeasible(f, result);
    }

    // ---------------------------------------------------------------- 준비·검증

    private class Fixture {
        TripInput in;
        TripPlan plan;
        DayPlan day;
        DayContext ctx;
        List<Place> tripPlaces = new ArrayList<>();
        Set<String> tripIds = new HashSet<>();

        ReplaceResult replace(String theme, Set<String> rejected, int... indexes) {
            List<DayItem> items = new ArrayList<>();
            for (SimulatedItinerary it : day.getItems()) {
                items.add(new DayItem(it.getType(), it.getPlace(), it.getDisplayName(), it.getStartMin(), it.getEndMin()));
            }
            for (int index : indexes) items.get(index).replace = true;
            return planService.replaceInDay(in.request, in.candidates, ctx, items, tripPlaces, rejected, theme);
        }
    }

    private Fixture fixture(TripInput in, int dayNumber) {
        Fixture f = new Fixture();
        f.in = in;
        f.plan = planService.planTrip(in);
        f.day = f.plan.getDays().get(dayNumber - 1);
        List<SimulatedItinerary> items = f.day.getItems();
        Place start = items.get(0).getType() == Type.START ? items.get(0).getPlace() : null;
        Place end = items.get(items.size() - 1).getType() == Type.END ? items.get(items.size() - 1).getPlace() : null;
        f.ctx = planService.buildDayContext(in.request, dayNumber, f.plan.getDays().size(), start, end);
        for (DayPlan d : f.plan.getDays()) {
            for (SimulatedItinerary it : d.getItems()) {
                if (it.getPlace() == null) continue;
                f.tripPlaces.add(it.getPlace());
                f.tripIds.add(it.getPlace().getPlaceId());
            }
        }
        return f;
    }

    /** 그 날 첫 번째 식당(food=true) 또는 첫 번째 관광지(food=false)의 위치 */
    private static int firstIndex(DayPlan day, boolean food) {
        for (int i = 0; i < day.getItems().size(); i++) {
            SimulatedItinerary it = day.getItems().get(i);
            if (it.getType() != Type.VISIT) continue;
            PlaceKind kind = PlaceKind.of(it.getPlace());
            if (food ? (kind == PlaceKind.RESTAURANT || kind == PlaceKind.BAR) : !kind.isFood()) return i;
        }
        throw new IllegalStateException("테스트 전제: 그 날에 해당 유형의 방문지가 있어야 한다");
    }

    private static List<String> names(List<SimulatedItinerary> items) {
        return items.stream().map(i -> i.getDisplayName() + " " + i.getTime() + "~" + i.getEndTime()).collect(Collectors.toList());
    }

    /** 고르지 않은 줄(도착 앵커 제외)은 이름·시작·종료 시각이 모두 그대로여야 한다. */
    private static void assertOthersUntouched(Fixture f, ReplaceResult result, int replacedIndex) {
        List<SimulatedItinerary> before = new ArrayList<>(f.day.getItems());
        before.remove(replacedIndex);
        Set<String> newIds = result.replacements.stream().filter(r -> r.changed).map(r -> r.newPlace.getPlaceId()).collect(Collectors.toSet());
        Set<String> originalFree = f.day.getItems().stream().filter(i -> i.getType() == Type.FREE)
                .map(i -> i.getTime() + "~" + i.getEndTime()).collect(Collectors.toSet());
        List<SimulatedItinerary> after = result.items.stream()
                .filter(i -> i.getPlace() == null || !newIds.contains(i.getPlace().getPlaceId()))
                .filter(i -> i.getType() != Type.FREE || originalFree.contains(i.getTime() + "~" + i.getEndTime()))   // 새로 생긴 자유 시간 제외
                .collect(Collectors.toList());

        assertEquals(before.size(), after.size(), "줄 수가 달라졌다\n" + names(before) + "\n" + names(after));
        for (int i = 0; i < before.size(); i++) {
            SimulatedItinerary a = before.get(i);
            SimulatedItinerary b = after.get(i);
            assertEquals(a.getDisplayName(), b.getDisplayName());
            boolean endAfterReplaced = a.getType() == Type.END && replacedIndex == f.day.getItems().size() - 2;
            if (!endAfterReplaced) {
                assertEquals(a.getStartMin(), b.getStartMin(), a.getDisplayName() + " 시작 시각이 바뀌었다");
                assertEquals(a.getEndTime(), b.getEndTime(), a.getDisplayName() + " 종료 시각이 바뀌었다");
            }
        }
    }

    /** 결과 하루 일정이 실제로 다닐 수 있는가: 시간 순서, 이동 시간, 영업시간, 일과 종료·출국 마감 */
    private static void assertFeasible(Fixture f, ReplaceResult result) {
        int clock = Integer.MIN_VALUE;
        Double lat = null;
        Double lng = null;
        Set<String> seen = new HashSet<>();
        Set<String> newIds = result.replacements.stream().filter(r -> r.changed).map(r -> r.newPlace.getPlaceId()).collect(Collectors.toSet());

        for (SimulatedItinerary item : result.items) {
            String label = item.getDisplayName() + " " + item.getTime();
            assertTrue(item.getStartMin() >= clock, label + " 시간이 거꾸로 감\n" + names(result.items));
            if (lat != null && item.getLatitude() != null && item.getType() != Type.FREE && item.getType() != Type.MEAL) {
                int need = TravelTimeEstimator.minutes(lat, lng, item.getLatitude(), item.getLongitude(), f.in.request.getTransportation());
                assertTrue(item.getStartMin() - clock >= need, label + " 이동 시간 부족 (필요 " + need + "분, 확보 "
                        + (item.getStartMin() - clock) + "분)\n" + names(result.items));
                assertEquals(need, item.getTravelMinutes(), label + " 이동 시간 표시가 실제와 다르다");
            }
            clock = item.getEndMin() != null ? item.getEndMin() : item.getStartMin();
            if (item.getLatitude() != null) {
                lat = item.getLatitude();
                lng = item.getLongitude();
            }
            if (item.getType() == Type.VISIT) {
                assertTrue(seen.add(item.getPlace().getPlaceId()), label + " 중복 방문");
                assertTrue(item.getEndMin() > item.getStartMin(), label + " 체류 시간이 0 이하");
                if (newIds.contains(item.getPlace().getPlaceId())) {
                    List<int[]> open = OpeningHours.intervalsOn(item.getPlace(), f.ctx.getDate());
                    if (open != null) {
                        assertTrue(open.stream().anyMatch(iv -> item.getStartMin() >= iv[0] && item.getEndMin() <= iv[1]),
                                label + "~" + item.getEndTime() + " 영업시간 밖 (" + item.getPlace().getOpeningHours() + ")");
                    }
                    assertTrue(item.getEndMin() <= f.ctx.getEndMin(), label + " 일과 종료(" + TimeUtil.format(f.ctx.getEndMin()) + ") 이후에 끝난다");
                }
            }
        }
        if (f.ctx.getHardDeadlineMin() != null) {
            SimulatedItinerary end = result.items.get(result.items.size() - 1);
            assertTrue(end.getStartMin() <= f.ctx.getHardDeadlineMin(), "공항 도착 " + end.getTime() + " 이 마감보다 늦다");
        }
    }
}
