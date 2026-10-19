package com.travel.planner.service;

import com.travel.planner.dto.LongTripSettingsDto;
import com.travel.planner.dto.PlanRequest;
import com.travel.planner.entity.User;
import com.travel.planner.service.PlanService.DayPlan;
import com.travel.planner.service.PlanService.SimulatedItinerary.Type;
import com.travel.planner.service.PlanService.TripInput;
import com.travel.planner.service.PlanService.TripPlan;
import com.travel.planner.util.PlaceKind;
import com.travel.planner.util.TripProfile;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 중기(10일 이상)의 가벼운 날 하루 + 마이페이지 장기 일정 설정(여유일 간격·관광 시간) */
class PaceSettingsTest {

    private final PlanService planService = new PlanService();

    private static List<Integer> lightDays(TripPlan plan) {
        return plan.getDays().stream().filter(d -> d.getPaceNote() != null).map(DayPlan::getDayNumber).collect(Collectors.toList());
    }

    private static int sightMinutes(DayPlan day) {
        return day.getItems().stream().filter(i -> i.getType() == Type.VISIT && i.getPlace() != null)
                .filter(i -> { PlaceKind k = PlaceKind.of(i.getPlace()); return k == PlaceKind.ATTRACTION || k == PlaceKind.SHOPPING || k == PlaceKind.THEME_PARK; })
                .mapToInt(i -> i.getEndMin() - i.getStartMin()).sum();
    }

    private static long sights(DayPlan day) {
        return day.getItems().stream().filter(i -> i.getType() == Type.VISIT && i.getPlace() != null)
                .filter(i -> { PlaceKind k = PlaceKind.of(i.getPlace()); return k == PlaceKind.ATTRACTION || k == PlaceKind.SHOPPING || k == PlaceKind.THEME_PARK; })
                .count();
    }

    @Test
    void mediumLightDayIsAtTwoThirds() {
        assertEquals(0, TripProfile.mediumLightDay(7), "단기는 없음");
        assertEquals(0, TripProfile.mediumLightDay(9), "중기라도 10일 미만은 없음");
        assertEquals(7, TripProfile.mediumLightDay(10));
        assertEquals(8, TripProfile.mediumLightDay(12));
        assertEquals(9, TripProfile.mediumLightDay(14));
        assertEquals(0, TripProfile.mediumLightDay(15), "장기는 장기 규칙");
    }

    @Test
    void tenDayTripGetsOneLightDayOnDaySeven() {
        TripPlan plan = planService.planTrip(LongTripTest.tokyoTrip(10, true));
        assertEquals(List.of(7), lightDays(plan));
        DayPlan light = plan.getDays().get(6);
        assertTrue(light.getPaceNote().startsWith("가벼운 날"));
        assertTrue(light.getPaceNote().contains("3시간"), light.getPaceNote());
        assertTrue(sights(light) <= 1 || sightMinutes(light) <= 180, "관광 " + sightMinutes(light) + "분");
        assertTrue(light.getItems().get(light.getItems().size() - 1).getStartMin() <= PlanService.TOUR_LIGHT_END_MIN + 60, "일찍 마친다");

        assertEquals(List.of(), lightDays(planService.planTrip(LongTripTest.tokyoTrip(9, true))), "9일은 그대로");
        assertEquals(List.of(), lightDays(planService.planTrip(LongTripTest.tokyoTrip(6, false))), "단기는 그대로");
    }

    @Test
    void mediumLightDayMovesPastAFixedSchedule() {
        TripInput in = LongTripTest.tokyoTrip(12, false);
        PlanRequest.FixedScheduleInput fixed = new PlanRequest.FixedScheduleInput();
        fixed.setName("공연");
        fixed.setStartTime(LocalTime.of(19, 0));
        fixed.setEndTime(LocalTime.of(21, 0));
        fixed.setDayNumber(8);   // 12일 → 원래 8일째
        in.request.setFixedSchedules(List.of(fixed));
        assertEquals(List.of(9), lightDays(planService.planTrip(in)), "고정 일정이 있는 날은 건너뛰고 다음 날");
    }

    @Test
    void mediumTripIgnoresLongTripSettings() {
        TripInput in = LongTripTest.tokyoTrip(10, true);
        in.pace = new TripProfile.PaceSettings(2, 0, 2, 0);
        assertEquals(List.of(7), lightDays(planService.planTrip(in)), "마이페이지 설정은 장기(15일 이상)에만");
    }

    @Test
    void liveRestIntervalAndSightHoursFollowSettings() {
        TripInput in = LongTripTest.tokyoTrip(20, true);
        in.pace = new TripProfile.PaceSettings(4, 90, null, null);   // 4일마다, 관광 1시간 30분까지
        TripPlan plan = planService.planTrip(in);
        assertEquals(List.of(4, 8, 12, 16), lightDays(plan));
        DayPlan rest = plan.getDays().get(3);
        assertTrue(rest.getPaceNote().contains("1시간 30분"), rest.getPaceNote());
        assertTrue(sights(rest) >= 1, "관광 시간을 주면 여유일에도 관광지가 들어간다");
        assertTrue(sights(rest) <= 1 || sightMinutes(rest) <= 90, "관광 " + sightMinutes(rest) + "분");

        TripPlan none = planService.planTrip(withPace(LongTripTest.tokyoTrip(20, true), new TripProfile.PaceSettings(0, null, null, null)));
        assertEquals(List.of(), lightDays(none), "간격 0 = 여유일 없음");
    }

    @Test
    void tourLightIntervalAndSightHoursFollowSettings() {
        TripInput in = LongTripTest.tokyoTrip(20, false);
        in.pace = new TripProfile.PaceSettings(null, null, 5, 60);
        TripPlan plan = planService.planTrip(in);
        assertEquals(List.of(5, 10, 15), lightDays(plan));
        DayPlan light = plan.getDays().get(4);
        assertTrue(light.getPaceNote().contains("1시간"), light.getPaceNote());
        assertTrue(sights(light) <= 1 || sightMinutes(light) <= 60, "관광 " + sightMinutes(light) + "분");

        // 기본값은 예전 그대로: 투어 7일마다
        assertEquals(List.of(7, 14), lightDays(planService.planTrip(LongTripTest.tokyoTrip(20, false))));
    }

    private static TripInput withPace(TripInput in, TripProfile.PaceSettings pace) {
        in.pace = pace;
        return in;
    }

    @Test
    void settingsAreStoredOnTheUserWithDefaults() {
        User user = new User();
        TripProfile.PaceSettings pace = UserService.paceOf(user);
        assertEquals(5, pace.liveRestEvery);
        assertEquals(0, pace.liveRestSightMin);
        assertEquals(7, pace.tourLightEvery);
        assertEquals(180, pace.tourLightSightMin);

        user.setLiveRestEveryDays(3);
        user.setTourLightSightMinutes(150);
        pace = UserService.paceOf(user);
        assertEquals(3, pace.liveRestEvery);
        assertEquals(150, pace.tourLightSightMin);
        assertEquals(7, pace.tourLightEvery, "비워 둔 칸은 기본값");

        LongTripSettingsDto dto = LongTripSettingsDto.of(pace, false);
        assertEquals(2.5, dto.getTourLightSightHours(), 1e-9);
        assertEquals(0.0, dto.getLiveRestSightHours(), 1e-9);
    }

    @Test
    void settingsAreValidated() {
        assertEquals(0, UserService.checkEvery("간격", 0));
        assertEquals(30, UserService.checkEvery("간격", 30));
        assertThrows(ResponseStatusException.class, () -> UserService.checkEvery("간격", 1), "매일 여유일은 안 됨");
        assertThrows(ResponseStatusException.class, () -> UserService.checkEvery("간격", 31));
        assertEquals(90, UserService.checkHours("시간", 1.5));
        assertEquals(0, UserService.checkHours("시간", 0));
        assertEquals(600, UserService.checkHours("시간", 10));
        assertThrows(ResponseStatusException.class, () -> UserService.checkHours("시간", 1.25), "0.5시간 단위");
        assertThrows(ResponseStatusException.class, () -> UserService.checkHours("시간", 10.5));
        assertThrows(ResponseStatusException.class, () -> UserService.checkHours("시간", -1));
    }

    @Test
    void paceNoteWording() {
        assertEquals("여유일: 관광지 없이 동네 산책·카페·식사로 쉬어 가는 날", PlanService.paceNote(TripProfile.DayKind.LIVE_REST, 0));
        assertEquals("2시간 30분", PlanService.hoursText(150));
        assertEquals("30분", PlanService.hoursText(30));
        assertNull(PlanService.paceNote(TripProfile.DayKind.NORMAL, 0));
    }
}
