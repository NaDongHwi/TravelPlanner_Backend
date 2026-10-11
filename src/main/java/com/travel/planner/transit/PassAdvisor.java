package com.travel.planner.transit;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 교통 패스 추천. (DB·스프링과 무관한 순수 계산)
 *
 * 패스 하나의 값어치는 구간 하나가 아니라 "그 패스를 쓰는 동안 탄 것 전부"의 요금으로 따져야 한다.
 * 그래서 여행 전체의 승차를 모은 뒤, 패스마다 가장 이득이 큰 사용 기간을 찾아 패스 가격과 견준다.
 *
 * 고르는 방법 (탐욕):
 *   1. 모든 패스에 대해, 쓸 수 있는 모든 기간(1일권이면 날마다, 24시간권이면 승차 시각마다) 중 "덮는 요금 − 패스 가격"이 가장 큰 것을 찾는다.
 *   2. 그 값이 가장 큰 패스를 하나 고르고, 그 패스가 덮은 승차를 목록에서 뺀다.
 *   3. 남은 승차로 1~2 를 되풀이한다. 이득이 나는 패스가 더 없으면 끝낸다.
 * 같은 패스를 서로 다른 날에 두 번 사는 것도 이 과정에서 자연히 나온다.
 *
 * 패스가 승차를 덮는지는 운영사·교통수단 종류·노선 이름·지역 범위로 판단한다. 요금을 한 번 내는 단위(예: JR 을 이어 탄 구간)
 * 안의 모든 승차가 덮여야 그 요금을 아낀 것으로 본다. 일부만 덮이면(범위 밖까지 타고 가면) 아끼지 못한 것으로 친다.
 */
public final class PassAdvisor {

    private PassAdvisor() {}

    /** 이득이 이보다 작으면 추천하지 않는다 (엔) */
    static final int MIN_SAVING_YEN = 100;
    /** 이득이 패스 가격의 이 비율보다 작으면 추천하지 않는다 (요금이 추정값이라 근소한 차이는 믿기 어렵다) */
    static final double MIN_SAVING_RATIO = 0.05;
    /** 한 여행에 추천하는 패스 수의 상한 */
    static final int MAX_SUGGESTIONS = 6;

    /** 쓸 수 있는 요일 */
    public enum DayRule {
        /** 매일 */
        ANY,
        /** 평일만 (토·일·공휴일 제외) */
        WEEKDAY,
        /** 토·일·공휴일만 */
        HOLIDAY
    }

    /** 패스가 덮는 범위 한 줄. 여러 줄 중 하나에만 맞으면 덮인다 */
    public static final class Cover {
        /** 운영사 이름 원문. 철도망의 운영사는 같아야 하고, GTFS 사업자 이름은 이 글자를 포함하면 된다. 비어 있으면 운영사를 가리지 않는다 */
        public List<String> operators = new ArrayList<>();
        /** 교통수단 종류. 비어 있으면 신칸센을 뺀 전부 (신칸센은 반드시 적어야 덮인다) */
        public Set<RailCategory> categories = EnumSet.noneOf(RailCategory.class);
        /** 비어 있지 않으면 노선 이름이 이 중 하나를 포함해야 한다 */
        public List<String> lines = new ArrayList<>();
        /** 노선 이름이 이 중 하나를 포함하면 덮이지 않는다 */
        public List<String> exceptLines = new ArrayList<>();
        /** 비어 있지 않으면 타는 곳·내리는 곳이 모두 이 범위 안이어야 한다: {남쪽 위도, 서쪽 경도, 북쪽 위도, 동쪽 경도} */
        public List<double[]> boxes = new ArrayList<>();
        /** 이 역·정류장에서 타거나 내리는 승차는 덮이지 않는다 (예: 패스에서 빠진 역) */
        public List<String> exceptStations = new ArrayList<>();

        boolean covers(Leg leg) {
            if (!operators.isEmpty()) {
                boolean match = false;
                String operator = leg.operator == null ? "" : leg.operator;
                for (String name : operators) {
                    if (operator.equals(name) || (leg.scheduled && !name.isEmpty() && operator.contains(name))) {
                        match = true;
                        break;
                    }
                }
                if (!match) return false;
            }
            if (categories.isEmpty()) {
                if (leg.category == RailCategory.SHINKANSEN) return false;
            } else if (!categories.contains(leg.category)) {
                return false;
            }
            String line = leg.line == null ? "" : leg.line;
            if (!lines.isEmpty() && !containsAny(line, lines)) return false;
            if (containsAny(line, exceptLines)) return false;
            if (leg.from != null && exceptStations.contains(leg.from)) return false;
            if (leg.to != null && exceptStations.contains(leg.to)) return false;
            if (!boxes.isEmpty()) {
                if (!leg.hasCoordinates) return false;
                if (!inside(leg.fromLat, leg.fromLng) || !inside(leg.toLat, leg.toLng)) return false;
            }
            return true;
        }

        private boolean inside(double lat, double lng) {
            for (double[] box : boxes) {
                if (lat >= box[0] && lat <= box[2] && lng >= box[1] && lng <= box[3]) return true;
            }
            return false;
        }
    }

    public static final class Pass {
        public String key;
        public String name;
        public String city;
        public int priceYen;
        public Integer childPriceYen;
        /** 달력 기준 사용 일수 (1일권 = 1). 시간권이면 0 */
        public int days;
        /** 처음 쓴 때부터의 시간 (24시간권 = 24). 일수권이면 0 */
        public int hours;
        /** 일수권에서 연속한 날에만 쓸 수 있는가. false 면 기간 안의 아무 날이나 고를 수 있다 */
        public boolean consecutive = true;
        public DayRule dayRule = DayRule.ANY;
        /** HOLIDAY 규칙에서 휴일처럼 쓸 수 있는 "매월 며칠" (예: 나고야 도니치에코의 8일) */
        public Set<Integer> extraDaysOfMonth = new TreeSet<>();
        /** HOLIDAY 규칙에서 연말연시(12/29~1/3)도 휴일로 치는가 */
        public boolean yearEndAsHoliday;
        public List<Cover> covers = new ArrayList<>();

        /** 이 패스로 그 승차(요금 묶음)를 모두 탈 수 있는가 */
        public boolean covers(Ride ride) {
            if (ride.legs.isEmpty()) return false;
            for (Leg leg : ride.legs) {
                boolean any = false;
                for (Cover cover : covers) {
                    if (cover.covers(leg)) {
                        any = true;
                        break;
                    }
                }
                if (!any) return false;
            }
            return true;
        }

        /** 그 날짜에 쓸 수 있는가. 날짜를 모르면 요일 조건이 없는 패스만 쓸 수 있다고 본다 */
        public boolean usableOn(LocalDate date) {
            if (dayRule == DayRule.ANY) return true;
            if (date == null) return false;
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY
                    || JapanHolidays.isHoliday(date);
            if (dayRule == DayRule.WEEKDAY) return !weekend;
            return weekend || extraDaysOfMonth.contains(date.getDayOfMonth()) || (yearEndAsHoliday && JapanHolidays.isYearEnd(date));
        }

        int windowMinutes() {
            return hours * 60;
        }
    }

    /** 승차 한 번 (요금 묶음 안의 한 구간) */
    public static final class Leg {
        public String operator;
        public RailCategory category;
        public String line;
        public String from;
        public String to;
        /** GTFS 노선인가 (사업자 이름을 부분 일치로 본다) */
        public boolean scheduled;
        public boolean hasCoordinates;
        public double fromLat;
        public double fromLng;
        public double toLat;
        public double toLng;
    }

    /** 요금을 한 번 낸 단위 */
    public static final class Ride {
        /** 여행 몇째 날인가 (1부터) */
        public int day;
        /** 그 날의 날짜. 모르면 null */
        public LocalDate date;
        /** 타는 시각 (그 날 0시부터의 분) */
        public int minute;
        public int fareYen;
        /** 요금이 추정값인가 (운영사 요금표나 역 쌍 요금이 아니다) */
        public boolean estimated;
        public List<Leg> legs = new ArrayList<>();

        long clock() {
            return (long) (day - 1) * 1440 + minute;
        }
    }

    public static final class Suggestion {
        public Pass pass;
        /** 쓰는 날들 (여행 일차, 오름차순). 시간권은 승차가 있는 날들 */
        public List<Integer> days = new ArrayList<>();
        /** 시간권: 처음 쓰는 날과 시각(그 날 0시부터의 분). 일수권이면 -1 */
        public int startMinute = -1;
        /** 이 패스로 타는 구간의 요금 합 */
        public int coveredFareYen;
        /** 아끼는 금액 = 덮는 요금 − 패스 가격 */
        public int savingYen;
        public int rideCount;
        /** 덮는 요금에 추정값이 섞여 있는가 */
        public boolean estimated;
        public List<Ride> rides = new ArrayList<>();
    }

    /**
     * @param passes 쓸 수 있는 패스 목록
     * @param rides  여행 전체의 승차 (요금 묶음 단위)
     * @return 이득이 큰 순서. 이득이 나는 패스가 없으면 빈 목록
     */
    public static List<Suggestion> advise(List<Pass> passes, List<Ride> rides) {
        List<Suggestion> chosen = new ArrayList<>();
        if (passes == null || passes.isEmpty() || rides == null || rides.isEmpty()) return chosen;
        List<Ride> remaining = new ArrayList<>();
        for (Ride ride : rides) {
            if (ride.fareYen > 0 && !ride.legs.isEmpty()) remaining.add(ride);
        }
        while (!remaining.isEmpty() && chosen.size() < MAX_SUGGESTIONS) {
            Suggestion best = null;
            for (Pass pass : passes) {
                Suggestion candidate = bestUse(pass, remaining);
                if (candidate == null || !worthIt(candidate)) continue;
                if (best == null || candidate.savingYen > best.savingYen
                        || (candidate.savingYen == best.savingYen && candidate.pass.priceYen < best.pass.priceYen)) {
                    best = candidate;
                }
            }
            if (best == null) break;
            chosen.add(best);
            remaining.removeAll(new HashSet<>(best.rides));
        }
        return chosen;
    }

    static boolean worthIt(Suggestion s) {
        return s.savingYen >= MIN_SAVING_YEN && s.savingYen >= s.pass.priceYen * MIN_SAVING_RATIO;
    }

    /** 이 패스를 한 장 샀을 때 가장 이득인 사용 기간. 덮는 승차가 없으면 null */
    public static Suggestion bestUse(Pass pass, List<Ride> rides) {
        if (pass.priceYen <= 0 || (pass.days <= 0 && pass.hours <= 0)) return null;
        List<Ride> covered = new ArrayList<>();
        for (Ride ride : rides) {
            if (pass.usableOn(ride.date) && pass.covers(ride)) covered.add(ride);
        }
        if (covered.isEmpty()) return null;

        List<Ride> bestSet = null;
        int bestSum = -1;
        int bestStart = -1;
        if (pass.hours > 0) {
            // 처음 탄 때부터 N시간: 덮이는 승차의 시각마다 그때 쓰기 시작한 경우를 따져 본다
            covered.sort((a, b) -> Long.compare(a.clock(), b.clock()));
            for (int i = 0; i < covered.size(); i++) {
                long start = covered.get(i).clock();
                long end = start + pass.windowMinutes();
                List<Ride> set = new ArrayList<>();
                int sum = 0;
                for (int j = i; j < covered.size() && covered.get(j).clock() < end; j++) {
                    set.add(covered.get(j));
                    sum += covered.get(j).fareYen;
                }
                if (sum > bestSum) {
                    bestSum = sum;
                    bestSet = set;
                    bestStart = covered.get(i).minute;
                }
            }
        } else if (pass.consecutive) {
            Set<Integer> startDays = new TreeSet<>();
            for (Ride ride : covered) startDays.add(ride.day);
            for (int start : startDays) {
                List<Ride> set = new ArrayList<>();
                int sum = 0;
                for (Ride ride : covered) {
                    if (ride.day >= start && ride.day < start + pass.days) {
                        set.add(ride);
                        sum += ride.fareYen;
                    }
                }
                if (sum > bestSum) {
                    bestSum = sum;
                    bestSet = set;
                }
            }
        } else {
            // 아무 날이나 N일: 덮는 요금이 큰 날부터 고른다
            java.util.Map<Integer, Integer> sumByDay = new java.util.TreeMap<>();
            for (Ride ride : covered) sumByDay.merge(ride.day, ride.fareYen, Integer::sum);
            List<Integer> daysByValue = new ArrayList<>(sumByDay.keySet());
            daysByValue.sort((a, b) -> sumByDay.get(b) != sumByDay.get(a).intValue() ? sumByDay.get(b) - sumByDay.get(a) : a - b);
            Set<Integer> picked = new HashSet<>(daysByValue.subList(0, Math.min(pass.days, daysByValue.size())));
            bestSet = new ArrayList<>();
            bestSum = 0;
            for (Ride ride : covered) {
                if (picked.contains(ride.day)) {
                    bestSet.add(ride);
                    bestSum += ride.fareYen;
                }
            }
        }
        if (bestSet == null || bestSet.isEmpty()) return null;

        Suggestion s = new Suggestion();
        s.pass = pass;
        s.rides = bestSet;
        s.coveredFareYen = bestSum;
        s.savingYen = bestSum - pass.priceYen;
        s.rideCount = bestSet.size();
        s.startMinute = pass.hours > 0 ? bestStart : -1;
        Set<Integer> days = new TreeSet<>();
        for (Ride ride : bestSet) {
            days.add(ride.day);
            if (ride.estimated) s.estimated = true;
        }
        s.days = new ArrayList<>(days);
        return s;
    }

    private static boolean containsAny(String text, List<String> parts) {
        for (String part : parts) {
            if (!part.isEmpty() && text.contains(part)) return true;
        }
        return false;
    }

    /** 경로의 요금 묶음을 패스 계산용 승차로 바꾼다 (날짜·시각은 호출한 쪽에서 채운다) */
    public static List<Ride> ridesOf(RailGraph.Route route) {
        if (route == null) return Collections.emptyList();
        List<Ride> rides = new ArrayList<>();
        for (RailGraph.FareBlock block : route.fareBlocks) {
            Ride ride = new Ride();
            ride.fareYen = block.fareYen;
            ride.estimated = block.basis == FareBook.Basis.ESTIMATE;
            for (RailGraph.Step step : block.rides) {
                Leg leg = new Leg();
                leg.operator = step.operator;
                leg.category = step.category;
                leg.line = step.line;
                leg.from = step.from;
                leg.to = step.to;
                leg.scheduled = step.scheduled;
                leg.hasCoordinates = step.hasCoordinates;
                leg.fromLat = step.fromLat;
                leg.fromLng = step.fromLng;
                leg.toLat = step.toLat;
                leg.toLng = step.toLng;
                ride.legs.add(leg);
            }
            rides.add(ride);
        }
        return rides;
    }

    /** 패스가 덮는 노선 이름들 (추천 문구에 쓴다. 원문, 중복 없이 최대 limit 개) */
    public static List<String> linesOf(Suggestion s, int limit) {
        Set<String> lines = new LinkedHashSet<>();
        for (Ride ride : s.rides) {
            for (Leg leg : ride.legs) {
                if (leg.line != null && !leg.line.isBlank() && lines.size() < limit) lines.add(leg.line);
            }
        }
        return new ArrayList<>(lines);
    }
}
