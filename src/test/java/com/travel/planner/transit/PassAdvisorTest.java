package com.travel.planner.transit;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 교통 패스 추천 테스트: 여행 전체의 요금 합계와 견주기, 사용 기간 고르기, 요일 조건, 이용 범위 판단.
 */
class PassAdvisorTest {

    private static final String METRO = "東京地下鉄";
    private static final String TOEI = "東京都";
    private static final String JR_EAST = "東日本旅客鉄道";

    // ---------------------------------------------------------------- 합계로 견주기

    @Test
    void passIsRecommendedOnlyWhenTheDaysRidesAddUpToMoreThanItsPrice() {
        PassAdvisor.Pass dayPass = dayPass("metro-1day", 700, 1, cover(METRO));

        // 하루에 180엔 세 번 = 540엔: 700엔짜리 패스는 손해
        List<PassAdvisor.Ride> few = rides(ride(1, 540, 180, METRO), ride(1, 720, 180, METRO), ride(1, 900, 180, METRO));
        assertTrue(PassAdvisor.advise(List.of(dayPass), few).isEmpty());

        // 다섯 번 = 1,060엔: 360엔 이득. 구간 하나(260엔)만 보면 손해지만 하루 전체로는 이득이다
        List<PassAdvisor.Ride> many = rides(ride(1, 540, 180, METRO), ride(1, 660, 260, METRO), ride(1, 780, 180, METRO),
                ride(1, 900, 180, METRO), ride(1, 1080, 260, METRO));
        List<PassAdvisor.Suggestion> result = PassAdvisor.advise(List.of(dayPass), many);
        assertEquals(1, result.size());
        assertEquals(1060, result.get(0).coveredFareYen);
        assertEquals(360, result.get(0).savingYen);
        assertEquals(5, result.get(0).rideCount);
        assertEquals(List.of(1), result.get(0).days);
        assertEquals(-1, result.get(0).startMinute, "일수권에는 시작 시각이 없다");
    }

    @Test
    void smallSavingIsNotWorthRecommending() {
        // 50엔 이득: 추정이 섞인 요금으로는 믿기 어려운 차이라 추천하지 않는다
        PassAdvisor.Pass dayPass = dayPass("p", 700, 1, cover(METRO));
        assertTrue(PassAdvisor.advise(List.of(dayPass), rides(ride(1, 540, 750, METRO))).isEmpty());
        // 비싼 패스는 가격의 5% 이상 아껴야 한다 (50,000엔 패스에 1,000엔 이득은 추천하지 않음)
        PassAdvisor.Pass railPass = dayPass("jrp", 50000, 7, cover(JR_EAST, RailCategory.JR));
        assertTrue(PassAdvisor.advise(List.of(railPass), rides(jrRide(1, 600, 51000))).isEmpty());
        assertEquals(1, PassAdvisor.advise(List.of(railPass), rides(jrRide(1, 600, 53000))).size());
    }

    @Test
    void theBestDayIsChosenAndOtherDaysArePaidNormally() {
        PassAdvisor.Pass dayPass = dayPass("p", 700, 1, cover(METRO));
        List<PassAdvisor.Ride> rides = rides(
                ride(1, 540, 300, METRO), ride(1, 900, 300, METRO),                                   // 600엔: 손해
                ride(2, 540, 400, METRO), ride(2, 700, 400, METRO), ride(2, 900, 400, METRO),         // 1,200엔: 이득
                ride(3, 540, 200, METRO));
        List<PassAdvisor.Suggestion> result = PassAdvisor.advise(List.of(dayPass), rides);
        assertEquals(1, result.size());
        assertEquals(List.of(2), result.get(0).days);
        assertEquals(500, result.get(0).savingYen);
    }

    @Test
    void theSamePassCanBeBoughtAgainOnAnotherDay() {
        PassAdvisor.Pass dayPass = dayPass("p", 700, 1, cover(METRO));
        List<PassAdvisor.Ride> rides = rides(
                ride(1, 540, 500, METRO), ride(1, 900, 500, METRO),
                ride(2, 540, 100, METRO),
                ride(3, 540, 600, METRO), ride(3, 900, 600, METRO));
        List<PassAdvisor.Suggestion> result = PassAdvisor.advise(List.of(dayPass), rides);
        assertEquals(2, result.size());
        assertEquals(List.of(3), result.get(0).days, "이득이 큰 날부터");
        assertEquals(500, result.get(0).savingYen);
        assertEquals(List.of(1), result.get(1).days);
        assertEquals(300, result.get(1).savingYen);
    }

    @Test
    void multiDayPassCoversConsecutiveDays() {
        PassAdvisor.Pass threeDays = dayPass("p", 2000, 3, cover(METRO));
        List<PassAdvisor.Ride> rides = rides(
                ride(1, 540, 900, METRO), ride(2, 540, 100, METRO), ride(3, 540, 900, METRO),
                ride(4, 540, 900, METRO), ride(5, 540, 900, METRO), ride(6, 540, 100, METRO));
        List<PassAdvisor.Suggestion> result = PassAdvisor.advise(List.of(threeDays), rides);
        assertEquals(1, result.size());
        assertEquals(List.of(3, 4, 5), result.get(0).days, "연속 3일 중 합계가 가장 큰 3~5일차");
        assertEquals(700, result.get(0).savingYen);

        // 아무 날이나 고를 수 있는 패스는 요금이 큰 날 셋을 고른다
        PassAdvisor.Pass anyThree = dayPass("p", 2000, 3, cover(METRO));
        anyThree.consecutive = false;
        result = PassAdvisor.advise(List.of(anyThree), rides.subList(0, 4));
        assertEquals(List.of(1, 3, 4), result.get(0).days);
        assertEquals(700, result.get(0).savingYen);
    }

    // ---------------------------------------------------------------- 시간권

    @Test
    void hourPassRunsFromTheFirstRideAcrossMidnight() {
        PassAdvisor.Pass h24 = hourPass("p", 1000, 24, cover(METRO));
        List<PassAdvisor.Ride> rides = rides(
                ride(1, 9 * 60, 300, METRO),          // 1일차 09:00
                ride(1, 15 * 60, 400, METRO),         // 1일차 15:00
                ride(1, 20 * 60, 400, METRO),
                ride(2, 10 * 60, 400, METRO),         // 2일차 10:00: 15:00 에 쓰기 시작하면 아직 유효
                ride(2, 14 * 60 + 59, 400, METRO),    // 2일차 14:59: 유효
                ride(2, 15 * 60, 400, METRO));        // 2일차 15:00: 딱 24시간 뒤라 무효

        List<PassAdvisor.Suggestion> result = PassAdvisor.advise(List.of(h24), rides);
        assertEquals(1, result.size());
        PassAdvisor.Suggestion s = result.get(0);
        assertEquals(15 * 60, s.startMinute, "1일차 15:00 에 처음 쓰는 것이 가장 이득");
        assertEquals(List.of(1, 2), s.days);
        assertEquals(1600, s.coveredFareYen);
        assertEquals(4, s.rideCount);
        assertEquals(600, s.savingYen);
    }

    @Test
    void longerHourPassWinsWhenItSavesMore() {
        PassAdvisor.Pass h24 = hourPass("24h", 1000, 24, cover(METRO));
        PassAdvisor.Pass h72 = hourPass("72h", 2000, 72, cover(METRO));
        List<PassAdvisor.Ride> rides = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            rides.add(ride(day, 9 * 60, 500, METRO));
            rides.add(ride(day, 13 * 60, 500, METRO));
            rides.add(ride(day, 18 * 60, 500, METRO));
        }
        List<PassAdvisor.Suggestion> result = PassAdvisor.advise(List.of(h24, h72), rides);
        assertEquals(1, result.size(), "72시간권 한 장이 사흘을 모두 덮는다");
        assertEquals("72h", result.get(0).pass.key);
        assertEquals(2500, result.get(0).savingYen);
    }

    // ---------------------------------------------------------------- 요일 조건

    @Test
    void weekdayAndHolidayPassesFollowTheCalendar() {
        PassAdvisor.Pass weekday = dayPass("weekday", 820, 1, cover(METRO));
        weekday.dayRule = PassAdvisor.DayRule.WEEKDAY;
        PassAdvisor.Pass holiday = dayPass("holiday", 620, 1, cover(METRO));
        holiday.dayRule = PassAdvisor.DayRule.HOLIDAY;
        List<PassAdvisor.Pass> passes = List.of(weekday, holiday);

        LocalDate friday = LocalDate.of(2026, 10, 9);
        LocalDate saturday = LocalDate.of(2026, 10, 10);
        LocalDate sportsDay = LocalDate.of(2026, 10, 12);      // 월요일이지만 공휴일(스포츠의 날)
        List<PassAdvisor.Ride> rides = rides(
                dated(ride(1, 540, 500, METRO), friday), dated(ride(1, 900, 500, METRO), friday),
                dated(ride(2, 540, 500, METRO), saturday), dated(ride(2, 900, 500, METRO), saturday),
                dated(ride(4, 540, 500, METRO), sportsDay), dated(ride(4, 900, 500, METRO), sportsDay));

        List<PassAdvisor.Suggestion> result = PassAdvisor.advise(passes, rides);
        assertEquals(3, result.size());
        for (PassAdvisor.Suggestion s : result) {
            int day = s.days.get(0);
            assertEquals(day == 1 ? "weekday" : "holiday", s.pass.key, day + "일차");
        }
    }

    @Test
    void holidayPassExtrasAndUnknownDates() {
        PassAdvisor.Pass donichi = dayPass("donichi", 620, 1, cover(METRO));
        donichi.dayRule = PassAdvisor.DayRule.HOLIDAY;
        donichi.extraDaysOfMonth.add(8);
        donichi.yearEndAsHoliday = true;

        assertTrue(donichi.usableOn(LocalDate.of(2026, 10, 8)), "매월 8일");
        assertFalse(donichi.usableOn(LocalDate.of(2026, 10, 7)));
        assertTrue(donichi.usableOn(LocalDate.of(2026, 12, 30)), "연말연시");
        assertTrue(donichi.usableOn(LocalDate.of(2026, 10, 11)), "일요일");
        // 날짜를 모르면 요일 조건이 있는 패스는 추천하지 않는다 (쓸 수 없는 날일 수 있다)
        assertFalse(donichi.usableOn(null));
        assertTrue(PassAdvisor.advise(List.of(donichi), rides(ride(1, 540, 900, METRO))).isEmpty());

        PassAdvisor.Pass strict = dayPass("strict", 620, 1, cover(METRO));
        strict.dayRule = PassAdvisor.DayRule.HOLIDAY;
        assertFalse(strict.usableOn(LocalDate.of(2026, 12, 30)), "연말연시를 휴일로 치지 않는 패스 (수요일)");
        assertFalse(strict.usableOn(LocalDate.of(2026, 10, 8)));
    }

    // ---------------------------------------------------------------- 이용 범위

    @Test
    void rideIsCoveredOnlyWhenEveryLegOfTheFareBlockIsCovered() {
        PassAdvisor.Pass subwayTicket = dayPass("p", 800, 1, cover(METRO, RailCategory.SUBWAY), cover(TOEI, RailCategory.SUBWAY));

        PassAdvisor.Ride metroOnly = ride(1, 540, 500, METRO);
        PassAdvisor.Ride metroThenToei = ride(1, 600, 500, METRO);               // 요금 묶음은 운영사마다 따로지만, 범위 판단을 보려고 한 묶음에 넣는다
        metroThenToei.legs.add(leg(TOEI, RailCategory.SUBWAY, "大江戸線"));
        PassAdvisor.Ride intoPrivate = ride(1, 700, 500, METRO);
        intoPrivate.legs.add(leg("東急電鉄", RailCategory.PRIVATE, "東横線"));     // 직통으로 다른 회사 구간까지 간 승차

        assertTrue(subwayTicket.covers(metroOnly));
        assertTrue(subwayTicket.covers(metroThenToei));
        assertFalse(subwayTicket.covers(intoPrivate), "일부만 덮이면 아낀 것으로 치지 않는다");

        List<PassAdvisor.Suggestion> result = PassAdvisor.advise(List.of(subwayTicket), rides(metroOnly, metroThenToei, intoPrivate));
        assertEquals(1000, result.get(0).coveredFareYen);
        assertEquals(2, result.get(0).rideCount);
    }

    @Test
    void categoryLineStationAndAreaLimits() {
        // 도에이 지하철만 (같은 운영사의 노면전차·닛포리도네리 라이너는 제외)
        PassAdvisor.Cover toeiSubway = cover(TOEI, RailCategory.SUBWAY);
        toeiSubway.exceptLines.add("日暮里・舎人ライナー");
        assertTrue(toeiSubway.covers(leg(TOEI, RailCategory.SUBWAY, "12号線大江戸線")));
        assertFalse(toeiSubway.covers(leg(TOEI, RailCategory.TRAM, "荒川線")));
        assertFalse(toeiSubway.covers(leg(TOEI, RailCategory.SUBWAY, "日暮里・舎人ライナー")));

        // 종류를 적지 않으면 신칸센을 뺀 전부
        PassAdvisor.Cover toeiAll = cover(TOEI);
        assertTrue(toeiAll.covers(leg(TOEI, RailCategory.TRAM, "荒川線")));
        PassAdvisor.Cover jrAll = cover(JR_EAST);
        assertTrue(jrAll.covers(leg(JR_EAST, RailCategory.JR, "中央線")));
        assertFalse(jrAll.covers(leg(JR_EAST, RailCategory.SHINKANSEN, "東北新幹線")), "신칸센은 적어야 포함된다");
        assertTrue(cover(JR_EAST, RailCategory.JR, RailCategory.SHINKANSEN).covers(leg(JR_EAST, RailCategory.SHINKANSEN, "東北新幹線")));

        // 특정 노선만
        PassAdvisor.Cover loopOnly = cover(JR_EAST, RailCategory.JR);
        loopOnly.lines.add("山手線");
        assertTrue(loopOnly.covers(leg(JR_EAST, RailCategory.JR, "山手線")));
        assertFalse(loopOnly.covers(leg(JR_EAST, RailCategory.JR, "中央線")));

        // 빠지는 역
        PassAdvisor.Cover osaka = cover("大阪市高速電気軌道");
        osaka.exceptStations.add("夢洲");
        PassAdvisor.Leg toYumeshima = leg("大阪市高速電気軌道", RailCategory.SUBWAY, "中央線");
        toYumeshima.from = "本町";
        toYumeshima.to = "夢洲";
        assertFalse(osaka.covers(toYumeshima));
        toYumeshima.to = "コスモスクエア";
        assertTrue(osaka.covers(toYumeshima));

        // 지역: 23구 안의 JR 만
        PassAdvisor.Cover wards = cover(JR_EAST, RailCategory.JR);
        wards.boxes.add(new double[]{35.54, 139.595, 35.79, 139.875});
        PassAdvisor.Leg inside = located(leg(JR_EAST, RailCategory.JR, "山手線"), 35.69, 139.70, 35.66, 139.70);
        PassAdvisor.Leg toDisney = located(leg(JR_EAST, RailCategory.JR, "京葉線"), 35.68, 139.77, 35.636, 139.883);
        assertTrue(wards.covers(inside));
        assertFalse(wards.covers(toDisney), "내리는 역이 범위 밖");
        assertFalse(wards.covers(leg(JR_EAST, RailCategory.JR, "山手線")), "좌표를 모르면 범위 안이라고 볼 수 없다");
    }

    @Test
    void busOperatorsMatchByPartOfTheAgencyNameButRailNamesMustBeExact() {
        PassAdvisor.Cover kyoto = cover("京都市");
        PassAdvisor.Leg cityBus = leg("京都市交通局", RailCategory.BUS, "205");
        cityBus.scheduled = true;
        assertTrue(kyoto.covers(cityBus), "GTFS 사업자 이름은 등록한 글자를 포함하면 된다");
        assertTrue(kyoto.covers(leg("京都市", RailCategory.SUBWAY, "烏丸線")));
        // 철도망의 운영사 이름은 같아야 한다 ("東京都" 패스가 "東京都交通局" 이 아닌 다른 철도에 걸리지 않도록)
        assertFalse(kyoto.covers(leg("京都市交通局", RailCategory.SUBWAY, "烏丸線")));
        PassAdvisor.Leg otherBus = leg("京都バス", RailCategory.BUS, "17");
        otherBus.scheduled = true;
        assertFalse(kyoto.covers(otherBus));
    }

    // ---------------------------------------------------------------- 여러 패스

    @Test
    void passesAreChosenGreedilyOnWhatRemains() {
        PassAdvisor.Pass subwayOnly = dayPass("subway", 700, 1, cover(METRO));
        PassAdvisor.Pass combo = dayPass("combo", 1100, 1, cover(METRO), cover(TOEI));
        List<PassAdvisor.Ride> rides = rides(
                ride(1, 540, 400, METRO), ride(1, 600, 400, METRO), ride(1, 700, 400, METRO),
                ride(1, 800, 300, TOEI), ride(1, 900, 300, TOEI));
        // 지하철만: 1,200 − 700 = 500 / 공통권: 1,800 − 1,100 = 700 → 공통권 한 장
        List<PassAdvisor.Suggestion> result = PassAdvisor.advise(List.of(subwayOnly, combo), rides);
        assertEquals(1, result.size());
        assertEquals("combo", result.get(0).pass.key);
        assertEquals(700, result.get(0).savingYen);

        // 도에이를 한 번만 타면 지하철 전용권이 낫다 (1,200 − 700 = 500 > 1,500 − 1,100 = 400)
        result = PassAdvisor.advise(List.of(subwayOnly, combo), rides.subList(0, 4));
        assertEquals(1, result.size());
        assertEquals("subway", result.get(0).pass.key);
    }

    @Test
    void estimatedFaresAreFlagged() {
        PassAdvisor.Pass dayPass = dayPass("p", 700, 1, cover(METRO));
        PassAdvisor.Ride guessed = ride(1, 540, 600, METRO);
        guessed.estimated = true;
        List<PassAdvisor.Suggestion> result = PassAdvisor.advise(List.of(dayPass), rides(guessed, ride(1, 700, 600, METRO)));
        assertTrue(result.get(0).estimated);
        result = PassAdvisor.advise(List.of(dayPass), rides(ride(1, 540, 600, METRO), ride(1, 700, 600, METRO)));
        assertFalse(result.get(0).estimated);
    }

    @Test
    void emptyInputsGiveNoSuggestion() {
        PassAdvisor.Pass dayPass = dayPass("p", 700, 1, cover(METRO));
        assertTrue(PassAdvisor.advise(null, rides(ride(1, 540, 900, METRO))).isEmpty());
        assertTrue(PassAdvisor.advise(List.of(dayPass), null).isEmpty());
        assertTrue(PassAdvisor.advise(List.of(dayPass), new ArrayList<>()).isEmpty());
        // 기간이 없는 잘못된 패스는 무시한다
        PassAdvisor.Pass broken = dayPass("broken", 700, 0, cover(METRO));
        assertNull(PassAdvisor.bestUse(broken, rides(ride(1, 540, 900, METRO))));
        assertNotNull(PassAdvisor.bestUse(dayPass, rides(ride(1, 540, 900, METRO))));
    }

    /**
     * 무작위 일정으로 추천 결과의 성질을 확인한다.
     *  - 추천한 패스들이 덮는 승차는 서로 겹치지 않는다 (같은 요금을 두 번 아낀 것으로 세지 않는다)
     *  - 덮는 요금 합과 이득이 실제 승차 요금과 맞는다
     *  - 패스가 하나뿐일 때 첫 추천은 모든 사용 기간을 다 따져 본 최댓값과 같다
     */
    @Test
    void randomTripsKeepTheSuggestionsConsistent() {
        Random random = new Random(20261011);
        String[] operators = {METRO, TOEI, JR_EAST, "東急電鉄"};
        for (int trial = 0; trial < 3000; trial++) {
            List<PassAdvisor.Ride> rides = new ArrayList<>();
            int days = 1 + random.nextInt(6);
            for (int day = 1; day <= days; day++) {
                int count = random.nextInt(7);
                for (int k = 0; k < count; k++) {
                    rides.add(ride(day, 6 * 60 + random.nextInt(16 * 60), 150 + 10 * random.nextInt(60), operators[random.nextInt(operators.length)]));
                }
            }
            List<PassAdvisor.Pass> passes = new ArrayList<>();
            passes.add(dayPass("metro-1d", 500 + 100 * random.nextInt(8), 1, cover(METRO)));
            passes.add(hourPass("subway-24h", 600 + 100 * random.nextInt(10), 24, cover(METRO), cover(TOEI)));
            passes.add(dayPass("subway-3d", 1500 + 100 * random.nextInt(15), 3, cover(METRO), cover(TOEI)));
            passes.add(dayPass("all-2d", 2000 + 100 * random.nextInt(30), 2, cover(METRO), cover(TOEI), cover(JR_EAST), cover("東急電鉄")));

            List<PassAdvisor.Suggestion> result = PassAdvisor.advise(passes, rides);
            Set<PassAdvisor.Ride> used = new HashSet<>();
            int previous = Integer.MAX_VALUE;
            for (PassAdvisor.Suggestion s : result) {
                int sum = 0;
                for (PassAdvisor.Ride ride : s.rides) {
                    assertTrue(used.add(ride), "같은 승차를 두 패스가 덮었다 (trial " + trial + ")");
                    assertTrue(s.pass.covers(ride));
                    sum += ride.fareYen;
                }
                assertEquals(sum, s.coveredFareYen);
                assertEquals(sum - s.pass.priceYen, s.savingYen);
                assertTrue(s.savingYen >= PassAdvisor.MIN_SAVING_YEN);
                assertTrue(s.savingYen <= previous, "이득이 큰 순서여야 한다");
                previous = s.savingYen;
                assertWindow(s, trial);
            }

            // 패스 하나만 놓고 보면, 고른 사용 기간이 가능한 모든 기간 중 최선이어야 한다
            PassAdvisor.Pass single = passes.get(random.nextInt(passes.size()));
            PassAdvisor.Suggestion best = PassAdvisor.bestUse(single, rides);
            int bruteForce = bruteForceBest(single, rides);
            assertEquals(bruteForce, best == null ? 0 : best.coveredFareYen, "trial " + trial + " " + single.key);
        }
    }

    /** 추천한 승차들이 실제로 패스의 유효 기간 안에 들어가는가 */
    private static void assertWindow(PassAdvisor.Suggestion s, int trial) {
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        int firstDay = Integer.MAX_VALUE;
        int lastDay = Integer.MIN_VALUE;
        for (PassAdvisor.Ride ride : s.rides) {
            first = Math.min(first, ride.clock());
            last = Math.max(last, ride.clock());
            firstDay = Math.min(firstDay, ride.day);
            lastDay = Math.max(lastDay, ride.day);
        }
        if (s.pass.hours > 0) {
            assertTrue(last - first < s.pass.hours * 60L, "시간권의 유효 시간을 넘었다 (trial " + trial + ")");
        } else {
            assertTrue(lastDay - firstDay < s.pass.days, "일수권의 유효 일수를 넘었다 (trial " + trial + ")");
        }
    }

    /** 가능한 모든 시작점을 따져 본 "덮는 요금"의 최댓값 */
    private static int bruteForceBest(PassAdvisor.Pass pass, List<PassAdvisor.Ride> rides) {
        int best = 0;
        for (PassAdvisor.Ride start : rides) {
            if (!pass.covers(start)) continue;
            int sum = 0;
            for (PassAdvisor.Ride ride : rides) {
                if (!pass.covers(ride)) continue;
                boolean inside = pass.hours > 0
                        ? ride.clock() >= start.clock() && ride.clock() < start.clock() + pass.hours * 60L
                        : ride.day >= start.day && ride.day < start.day + pass.days;
                if (inside) sum += ride.fareYen;
            }
            best = Math.max(best, sum);
        }
        return best;
    }

    // ---------------------------------------------------------------- 도우미

    static PassAdvisor.Pass dayPass(String key, int price, int days, PassAdvisor.Cover... covers) {
        PassAdvisor.Pass pass = new PassAdvisor.Pass();
        pass.key = key;
        pass.name = key;
        pass.priceYen = price;
        pass.days = days;
        pass.covers = new ArrayList<>(Arrays.asList(covers));
        return pass;
    }

    static PassAdvisor.Pass hourPass(String key, int price, int hours, PassAdvisor.Cover... covers) {
        PassAdvisor.Pass pass = dayPass(key, price, 0, covers);
        pass.hours = hours;
        return pass;
    }

    static PassAdvisor.Cover cover(String operator, RailCategory... categories) {
        PassAdvisor.Cover cover = new PassAdvisor.Cover();
        cover.operators.add(operator);
        cover.categories.addAll(Arrays.asList(categories));
        return cover;
    }

    static PassAdvisor.Leg leg(String operator, RailCategory category, String line) {
        PassAdvisor.Leg leg = new PassAdvisor.Leg();
        leg.operator = operator;
        leg.category = category;
        leg.line = line;
        return leg;
    }

    private static PassAdvisor.Leg located(PassAdvisor.Leg leg, double fromLat, double fromLng, double toLat, double toLng) {
        leg.hasCoordinates = true;
        leg.fromLat = fromLat;
        leg.fromLng = fromLng;
        leg.toLat = toLat;
        leg.toLng = toLng;
        return leg;
    }

    /** 지하철 한 번 */
    static PassAdvisor.Ride ride(int day, int minute, int fare, String operator) {
        PassAdvisor.Ride ride = new PassAdvisor.Ride();
        ride.day = day;
        ride.minute = minute;
        ride.fareYen = fare;
        ride.legs.add(leg(operator, operator.equals(JR_EAST) ? RailCategory.JR : operator.equals("東急電鉄") ? RailCategory.PRIVATE : RailCategory.SUBWAY, "線"));
        return ride;
    }

    private static PassAdvisor.Ride jrRide(int day, int minute, int fare) {
        return ride(day, minute, fare, JR_EAST);
    }

    private static PassAdvisor.Ride dated(PassAdvisor.Ride ride, LocalDate date) {
        ride.date = date;
        return ride;
    }

    private static List<PassAdvisor.Ride> rides(PassAdvisor.Ride... rides) {
        return new ArrayList<>(Arrays.asList(rides));
    }
}
